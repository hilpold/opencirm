/*******************************************************************************
 * Copyright 2014 Miami-Dade County
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.sharegov.cirm.legacy;

import static org.sharegov.cirm.OWL.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.xml.datatype.DatatypeFactory;
import mjson.Json;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.sharegov.cirm.BOntology;
import org.sharegov.cirm.Refs;
import org.sharegov.cirm.StartUp;
import org.sharegov.cirm.OWL;
import org.sharegov.cirm.owl.Model;
import org.sharegov.cirm.rest.LegacyEmulator;
import org.sharegov.cirm.utils.ActivityUtils;
import org.sharegov.cirm.utils.GenUtils;
import org.sharegov.cirm.utils.ThreadLocalStopwatch;


/**
 * The ActivityManager is responsible for creating and updating serviceActivities as well as executing configured triggers,
 * such as close case on outcome, execute email templates and trigger the creation of new activities or dependent service cases.
 * Furthermore, it's submitting tasks to the time machine for overdue callback checks.
 * 
 * @author Syed
 * @author Tom
 */
public class ActivityManager
{

	public static boolean DBG = true;
	public static boolean THROW_ALL_EXC = false;
	public static boolean USE_MESSAGE_MANAGER = true;
	public static boolean USE_TIME_MACHINE = true;
	
	public static final String ACTIVITY_AUTO = "auto";
	public static final String ACTIVITY_ERROR = "error";

	private final ActivityUtils utils = new ActivityUtils();
	
	/**
	 * Counts time machine calls since activitymanager creation for each task.
	 * As activitymanagers should be created once per transaction execution, it can ensure proper time machine task overwrites on retry.
	 * Task keys should remain constant during all retries of a transaction.
	 * Assuming single threaded execution.
	 */
	private final HashMap<String, Integer> timeMachineTaskToIndex = new HashMap<String, Integer>();

	/**
	 * Gets a unique taskId within this transaction execution that remains equal on transaction retries.
	 * Use with FRAGMENTA-OVERDUE-FRAGMENTB or DELAY-FRAGMENTA.
	 * Not thread safe, assuming single worker thread.
	 * hilpold
	 * @param task a task string that should be equal on retries and therefore not contain regenerated timestamps or any sequence ids.
	 * @return task + indexForThisTask
	 */
	private String getNextTimeMachineTaskIDFor(String task) 
	{
		Integer curIdx = timeMachineTaskToIndex.get(task);
		if (curIdx == null) 
			curIdx = 0;
		else 
			curIdx ++;
		timeMachineTaskToIndex.put(task, curIdx);
		return task + " idx " + curIdx.toString();
	}
	
	private String findAutoAssignment(OWLNamedIndividual rule, BOntology bo, OWLNamedIndividual serviceActivity, OWLNamedIndividual outcome)
	{
		if (rule.getIRI().equals(Model.legacy("AssignActivityToCaseCreator")))
		{
			OWLLiteral createdBy = bo.getDataProperty("isCreatedBy");
			if (createdBy != null)
				return createdBy.getLiteral();
		}
//		else if (rule.getIRI().toString().equals(OWLRefs.LEGACY_PREFIX + "#AssignActivityByGeoArea"))
//		{
//			OWLNamedIndividual srType = individual(bo.getTypeIRI());
//			OWLLiteral geoArea = dataProperty(srType, "legacy:hasGeoAreaCode");
//			
//		}		
		// All other rules:
		Set<OWLClass> S = reasoner().getTypes(rule, true).getFlattened();
		if (S.isEmpty())
			return null;
		OWLClass type = S.iterator().next();
		if (type.getIRI().equals(Model.legacy("AssignActivityToUserRule")))
		{
			OWLLiteral assignment = dataProperty(rule, "hasUsername");
			if (assignment != null)
				return assignment.getLiteral();
		}
		else if (type.getIRI().equals(Model.legacy("AssignActivityFromGeoAttribute")))
		{			
			// Get property info first
			Json locationInfo = Refs.gisClient.resolve().getLocationInfo(
					Double.parseDouble(bo.getDataProperty("hasXCoordinate").getLiteral()), 
					Double.parseDouble(bo.getDataProperty("hasYCoordinate").getLiteral()), 
					null, 3, 500);
			for (OWLNamedIndividual assignment : OWL.objectProperties(rule, "legacy:hasAssignmentRule"))
			{
				OWLLiteral attributeName = dataProperty(assignment, "hasName");
				OWLNamedIndividual layer = objectProperty(assignment, "hasGisLayer");
				OWLLiteral layerName = dataProperty(layer, "hasName");
				OWLLiteral valueExpression = dataProperty(assignment, "hasValue");
				OWLLiteral username = dataProperty(assignment, "hasUsername");
				if (Refs.gisClient.resolve().testLayerValue(locationInfo, layerName.getLiteral(), 
						  attributeName.getLiteral(), 
						  valueExpression.getLiteral()))
					return username.getLiteral();
			}
		} 
		else if (type.getIRI().equals(Model.legacy("AssignActivityToOutcomeEmail"))) 
		{
			return getAssignActivityToOutcomeEmail(outcome, bo);
		}
		return null;
	}
	
	/**
	 * Gets all activities defined for a ServiceCase type
	 * in the legacy ontology.
	 * 
	 * @param serviceCaseType
	 * @return A set containing the iri of each activity. 
	 * 
	 */
	public Set<OWLNamedIndividual> getActivities(OWLClass serviceCaseType)
	{
		Set<OWLNamedIndividual> activities = reasoner().getObjectPropertyValues(
				individual(serviceCaseType.getIRI()),
				objectProperty(fullIri("legacy:hasActivity"))
				).getFlattened();
		return activities;
	}
	
	/**
	 * Creates the serviceActivity axioms for the business object
	 * as defined by the ontology property isAutoCreate 'Y'
	 * for the activityType configuration of the
	 * supplied serviceCaseType.
	 * If the activity was disabled, it will not be created.  
	 * 
	 * @param serviceCaseType
	 * @param bo
	 */
	public void createDefaultActivities(OWLClass serviceCaseType, BOntology bo, Date createdDate, List<CirmMessage> messages)
	{
		for(OWLNamedIndividual activityType : getActivities(serviceCaseType))
		{
			if (utils.isAutoCreate(activityType) && !utils.isDisabled(activityType)) 
			{
				Date completedDate = null;
				OWLNamedIndividual outcome = null; 
    			List<CirmMessage> localMessages = new ArrayList<CirmMessage>();
    			createActivity(activityType, outcome, null, null, bo, createdDate, completedDate, null, localMessages);
    			for (CirmMessage lm : localMessages) 
    			{
  					lm.addExplanation("createDefaultActivities T: " + serviceCaseType.getIRI().getFragment());
    				messages.add(lm);
    			}
			}
		} //for
		createActivitiesFromQuestions(bo, messages);
	}
	
	/**
	 * Creates activities that should be created when case transitions into pending state, if any are defined for the type and intake method.
	 * @param serviceCaseType
	 * @param bo
	 * @param createdDate
	 * @param messages
	 */
	public void createAutoOnPendingActivities(BOntology bo, Date createdDate, List<CirmMessage> messages)
	{
		try {
			OWLNamedIndividual intakeMethodInd = bo.getObjectProperty("legacy:hasIntakeMethod");
			OWLNamedIndividual srTypeInd = OWL.individual("legacy:" + bo.getTypeIRI().getFragment());
			ThreadLocalStopwatch.start("START createAutoOnPendingActivities " + srTypeInd + " IntakeMethod: " + intakeMethodInd);
						Set<OWLNamedIndividual> autoActivities = utils.getAutoOnPendingActivities(srTypeInd, intakeMethodInd);
			for (OWLNamedIndividual activityType : autoActivities) {
				if (!utils.isDisabled(activityType)) 
				{	
					ThreadLocalStopwatch.now("Found AutoOnPendingActivity " + activityType);					
					Date completedDate = null;
    				OWLNamedIndividual outcome = null; 
        			List<CirmMessage> localMessages = new ArrayList<CirmMessage>();
        			createActivity(activityType, outcome, null, null, bo, createdDate, completedDate, null, localMessages);
        			for (CirmMessage lm : localMessages) 
        			{
       					lm.addExplanation("createAutoOnPendingActivities T: " + srTypeInd.getIRI().getFragment());
        				messages.add(lm);
        			}
    			}
    		} //for
			ThreadLocalStopwatch.start("END createAutoOnPendingActivities ");
		} catch(Exception e) {
			ThreadLocalStopwatch.fail("FAIL createAutoOnPendingActivities " + e);
			e.printStackTrace();
		}
	}

	/**
	 * Creates activities that should be created when case transitions into locked state, if any are defined for the bo type and intake method.
	 * @param serviceCaseType
	 * @param bo
	 * @param createdDate
	 * @param messages
	 */
	public void createAutoOnLockedActivities(BOntology bo, Date createdDate, List<CirmMessage> messages)
	{
		try {
    		OWLNamedIndividual intakeMethodInd = bo.getObjectProperty("legacy:hasIntakeMethod");		
			OWLNamedIndividual srTypeInd = OWL.individual("legacy:" + bo.getTypeIRI().getFragment());
    		ThreadLocalStopwatch.start("START createAutoOnLockedActivities " + srTypeInd + " IntakeMethod: " + intakeMethodInd);
    		Set<OWLNamedIndividual> autoActivities = utils.getAutoOnLockedActivities(srTypeInd, intakeMethodInd);
    		for (OWLNamedIndividual activityType : autoActivities) {
    			if (!utils.isDisabled(activityType)) 
    			{
    				ThreadLocalStopwatch.now("Found AutoOnLockedActivity " + activityType);
    				Date completedDate = null;
    				OWLNamedIndividual outcome = null; 
        			List<CirmMessage> localMessages = new ArrayList<CirmMessage>();
        			createActivity(activityType, outcome, null, null, bo, createdDate, completedDate, null, localMessages);
        			for (CirmMessage lm : localMessages) 
        			{
        				lm.addExplanation("createAutoOnLockedActivities T: " + srTypeInd.getIRI().getFragment());
        				messages.add(lm);
        			}
    			}
    		} //for
    		ThreadLocalStopwatch.start("END createAutoOnLockedActivities ");
		} catch(Exception e) {
			ThreadLocalStopwatch.fail("FAIL createAutoOnLockedActivities " + e);
			e.printStackTrace();
		}
	}
	
	/** 
	 * Creates an activity now and ignores a potentially configured occurday setting. 
	 * This method is used to create already scheduled activities on TM callback.
	 * 
	 * @param activityType
	 * @param bo
	 * @param messages
	 */
	public void createActivityOccurNow(OWLNamedIndividual activityType, BOntology bo, List<CirmMessage> messages) {
		createActivityImpl(activityType, null, null, null, bo, null, null, null, messages, true);
	}

	/**
	 * Creates an activity or schdules it for creation (occurdays > 0).
	 * @param activityType
	 * @param details
	 * @param isAssignedTo
	 * @param bo
	 * @param createdDate
	 * @param createdBy
	 * @param messages
	 */
	public void createActivity(OWLNamedIndividual activityType, String details, String isAssignedTo, BOntology bo, Date createdDate, String createdBy, List<CirmMessage> messages)
	{
		//Don't set the defaultOutcome, first the activityType needs to be accepted by the Assignee!!
//		Set<OWLNamedIndividual> outcomes = reasoner().getObjectPropertyValues(
//				individual(activity.getIRI()),
//				objectProperty("legacy:hasDefaultOutcome"))
//				.getFlattened();
//		if(outcomes.size() > 0)
//			createActivity(activity, outcomes.iterator().next(), details, isAssignedTo, bo);
//		else
			createActivity(activityType, null, details, isAssignedTo, bo, createdDate, null, createdBy, messages);
	}
	
	
	/**
	 * Creates an activity or schedules it for creation (occurdays >0).
	 * 
	 * @param activityType
	 * @param outcome
	 * @param details
	 * @param isAssignedTo
	 * @param bo
	 * @param createdDate
	 * @param completedDate
	 * @param createdBy
	 * @param messages a list of messages to add messages to.
	 * @return a Pair of message and template
	 */
	public void createActivity(OWLNamedIndividual activityType, 
							   OWLNamedIndividual outcome, 
							   String details, 
							   String isAssignedTo, 
							   BOntology bo,
							   Date createdDate,
							   Date completedDate,
							   String createdBy,
							   List<CirmMessage> messages)
	{
		createActivityImpl(activityType, outcome, details, isAssignedTo, bo, createdDate, completedDate, createdBy, messages, false);
	}
	
	/**
	 * Creates an activity with all side effect. Delays creation through Time Machine, if activity type's occurdays 
	 * if configured as > 0.0f and ignoreOccurdays is false. 
	 * @param activityType
	 * @param outcome
	 * @param details
	 * @param isAssignedTo
	 * @param bo
	 * @param createdDate
	 * @param completedDate
	 * @param createdBy
	 * @param messages a list of messages to add messages to.
	 * @param ignoreOccurDays ignore activity type occur day setting and create activity now.
	 * @return a Pair of message and template
	 */
	private void createActivityImpl(OWLNamedIndividual activityType, 
							   OWLNamedIndividual outcome, 
							   String details, 
							   String isAssignedTo, 
							   BOntology bo,
							   Date createdDate,
							   Date completedDate,
							   String createdBy,
							   List<CirmMessage> messages,
							   boolean ignoreOccurDays)
	{
		try
		{
			OWLOntology o = bo.getOntology();
			OWLOntologyManager manager = o.getOWLOntologyManager();
			OWLDataFactory factory = manager.getOWLDataFactory();
			OWLClass activityTypeClass = owlClass("legacy:ServiceActivity");
			Calendar now = Calendar.getInstance();
			Calendar calcreated = Calendar.getInstance();
			calcreated.setTime(createdDate != null ? createdDate : now.getTime());
			boolean useWorkWeek = false;
			float suspenseDaysConfiguredValue = determineSuspenseDays(activityType);
			float occurDaysConfiguredValue = determineOccurDays(activityType);
			//A) Check for immediate auto default outcome, if the activity type has it configured
			if (outcome == null && suspenseDaysConfiguredValue == 0) {				
				outcome = determineAutoDefaultOutcome(activityType);
			}
			//B) Check for user base date configured and user answer provided for occur or suspense usage.
			Date occurOrSuspenseBaseDate = determineDueBaseDate(bo, activityType, now.getTime());			
			Set<OWLLiteral> businessCodes = reasoner().getDataPropertyValues(
					activityType,
					dataProperty("legacy:hasBusinessCodes"));
			if(businessCodes.size() > 0)
			{
				useWorkWeek = businessCodes.iterator().next().getLiteral().contains("5DAYWORK");
			}
			/**
			 * If activityType hasOccurDays > 0, the set a timer for delayed 
			 * activity creation
			 */
			if(occurDaysConfiguredValue > 0 && !ignoreOccurDays)
			{
				scheduleActivityCreationOccurDays(bo, activityType, occurOrSuspenseBaseDate, occurDaysConfiguredValue, useWorkWeek, details, isAssignedTo);
				return;//activity creation will be delayed.
			}
			//Activity type
			OWLNamedIndividual serviceActivity = factory.getOWLNamedIndividual(
					fullIri(activityTypeClass.getIRI().getFragment() + Refs.idFactory.resolve().newId(null)));
			manager.addAxiom(o, factory.getOWLClassAssertionAxiom(activityTypeClass, serviceActivity));
			manager.addAxiom(o,factory.getOWLObjectPropertyAssertionAxiom(
								objectProperty("legacy:hasActivity")
								, serviceActivity, activityType));
			//System created date hasSysDateCreated always now
			OWLLiteral sysCreatedDateLiteral = factory.getOWLLiteral(DatatypeFactory.newInstance()
					.newXMLGregorianCalendar((GregorianCalendar)now)
					.toXMLFormat()
					,OWL2Datatype.XSD_DATE_TIME_STAMP);
			manager.addAxiom(o,
					factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("hasDateSysCreated"), serviceActivity, 
						sysCreatedDateLiteral
					));
			//Business created date hasDateCreated
			OWLLiteral createdDateLiteral = factory.getOWLLiteral(DatatypeFactory.newInstance()
										.newXMLGregorianCalendar((GregorianCalendar)calcreated)
										.toXMLFormat()
										,OWL2Datatype.XSD_DATE_TIME_STAMP);
			manager.addAxiom(o,
					factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("hasDateCreated"), serviceActivity, 
						createdDateLiteral
					));
			manager.addAxiom(o,
					factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("legacy:hasUpdatedDate"),serviceActivity, 
						createdDateLiteral
					));
			if(details != null)
			{
				manager.addAxiom(o,factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("legacy:hasDetails")
						, serviceActivity, details));
			}
			
			if (isAssignedTo == null)
			{
				Set<OWLNamedIndividual> assignRules = OWL.objectProperties(activityType, 
															"legacy:hasAssignmentRule"); 
				for (OWLNamedIndividual rule : assignRules)
				{
					isAssignedTo = findAutoAssignment(rule, bo, serviceActivity, outcome);
					if (isAssignedTo != null)
						break;
				}
			}
			
			if(isAssignedTo != null)
			{
				manager.addAxiom(o, factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("legacy:isAssignedTo"), serviceActivity, isAssignedTo));
			}
			
			if(createdBy != null)
			{
				manager.addAxiom(o,factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("isCreatedBy")
						, serviceActivity, createdBy));
			}

			if(outcome != null || completedDate != null)
			{
				if (outcome == null)
					outcome = OWL.individual("legacy:OUTCOME_COMPLETE");
				manager.addAxiom(o,factory.getOWLObjectPropertyAssertionAxiom(
							 objectProperty("legacy:hasOutcome")
							, serviceActivity, outcome));
				Calendar calcompleted = Calendar.getInstance();
				calcompleted.setTime(completedDate != null ? completedDate : calcreated.getTime()); 
				OWLLiteral completedDateLiteral = factory.getOWLLiteral(DatatypeFactory.newInstance()
						.newXMLGregorianCalendar((GregorianCalendar)calcompleted)
						.toXMLFormat()
						,OWL2Datatype.XSD_DATE_TIME_STAMP);				
				manager.addAxiom(o,
						factory.getOWLDataPropertyAssertionAxiom(
							dataProperty("legacy:hasCompletedTimestamp"),serviceActivity, 
							completedDateLiteral));
				List<CirmMessage> localMessages = new ArrayList<CirmMessage>();
				checkOutcomeTrigger(serviceActivity, activityType, outcome, isAssignedTo, bo, localMessages);
				for (CirmMessage lm : localMessages) 
				{
					lm.addExplanation("createactivity.checkoutcomeTrigger " + outcome.getIRI().getFragment() 
							+ " Act: " + activityType.getIRI().getFragment());
					messages.add(lm);
				}
			}
			if (suspenseDaysConfiguredValue > 0)
			{
				Date calculatedDueDate = calculateScheduledDelayDate(occurOrSuspenseBaseDate, suspenseDaysConfiguredValue, useWorkWeek);
				Calendar due = Calendar.getInstance();
				due.setTime(calculatedDueDate);
				OWLLiteral dueDate = factory.getOWLLiteral(DatatypeFactory.newInstance()
						.newXMLGregorianCalendar((GregorianCalendar)due)
						.toXMLFormat()
						,OWL2Datatype.XSD_DATE_TIME_STAMP);

				manager.addAxiom(o,
						factory.getOWLDataPropertyAssertionAxiom(
								dataProperty("legacy:hasDueDate"), serviceActivity, 
							dueDate
						));
				Set<OWLNamedIndividual> overdueActivity = reasoner().getObjectPropertyValues(
						activityType,
						objectProperty("legacy:hasOverdueActivity"))
						.getFlattened();
				if(overdueActivity.size() > 0)
				{
					OWLNamedIndividual overdueActivityType = overdueActivity.iterator().next();
					scheduleOverdueActivityCreationAtDueDate(bo, overdueActivityType, due, serviceActivity, activityType);
					String msg = "Scheduled overdue  " + overdueActivityType.getIRI().getFragment() + " 5dayWW: " + useWorkWeek + " " 
								+ occurOrSuspenseBaseDate + " + " + suspenseDaysConfiguredValue + " = " + calculatedDueDate;
					ThreadLocalStopwatch.now(msg);
				}
			}	
    		manager.addAxiom(o, factory.getOWLObjectPropertyAssertionAxiom(
    						objectProperty("legacy:hasServiceActivity")
    						, bo.getBusinessObject(), serviceActivity));
    		OWLNamedIndividual emailTemplate = objectProperty(activityType, "legacy:hasEmailTemplate");
    		if(emailTemplate != null && USE_MESSAGE_MANAGER)
    		{
    			if (hasAssignActivityToOutcomeEmail(activityType) && isAssignedTo == null)
    			{
    				//prevent email creation for serviceActivity as it should be created on a later update, where an outcome email is found.
    				System.out.println("createActivity: email creation prevented, because serviceActivity " + serviceActivity + " Type: " + activityType + " hasAssignActivityToOutcomeEmail, was executed and still noone assigned.");
    			} else 
    			{
    				CirmMimeMessage m = MessageManager.get().createMimeMessageFromTemplate(bo, dataProperty(activityType, "legacy:hasLegacyCode"), emailTemplate);
    				if (m!= null) {
    					m.addExplanation("createActivity " + serviceActivity.getIRI().getFragment() 
    							+ " Tpl: " + emailTemplate.getIRI().getFragment());
    					messages.add(m);
    				}
    				else {
    					System.err.println("ActivityManager: created Message was Null for " + (bo != null? bo.getObjectId() : bo) + "act: " + serviceActivity + " actT:" + activityType + " tmpl: " + emailTemplate);
    				}
    			}
    		}
    		OWLNamedIndividual smsTemplate = objectProperty(activityType, "legacy:hasSmsTemplate");
    		if(smsTemplate != null && USE_MESSAGE_MANAGER)
    		{
    			CirmSmsMessage m = MessageManager.get().createSmsMessageFromTemplate(bo, dataProperty(activityType, "legacy:hasLegacyCode"), smsTemplate);
    			if (m!= null) {
    				messages.add(m);
    			}
    			else {
    				System.err.println("ActivityManager: created SMS Message was Null for " + (bo != null? bo.getObjectId() : bo) + "act: " + serviceActivity + " actT:" + activityType + " tmpl: " + smsTemplate);
    			}
    		}

		}
		catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	private String getServerUrl()
	{
		try
		{
			OWLNamedIndividual operationsService = Refs.configSet.resolve().get("OperationsRestService");
			OWLLiteral osUrl = dataProperty(operationsService, "hasUrl");
			return osUrl.getLiteral();
		}catch(Exception e)
		{
			e.printStackTrace(System.out);
			if (THROW_ALL_EXC) 
				throw new RuntimeException(e);
			else
				return null;
		}		
	}

	/**
	 * Updates the passed in Business Ontology's ServiceActivity Axioms with the passed in parameter values.
	 * Any parameter may be null.
	 * 
	 * @param activityType : String representation of the ActivityType
	 * @param serviceActivity : String representation of the ServiceActivity to be updated
	 * @param outcome : String representation of Outcome of the ServiceActivity
	 * @param details : Specified Details for the ServiceActivity by the user
	 * @param assignedTo : The person's email/name/eNet No. to whom this ServiceActivity is assigned 
	 * @param modifiedBy : The eNet No. of the User who sent in the request
	 * @param isAccepted : true if the Assignee accepts the ServiceActivity
	 * @param bo : The Business Ontology of the Service Request
	 */
	public void updateActivity(String activityType, String serviceActivity, String outcome, 
			String details, String assignedTo, String modifiedBy, boolean isAccepted, BOntology bo, List<CirmMessage> messages)
	{
		if(serviceActivity != null)
		{
			OWLOntology o = bo.getOntology();
			OWLOntologyManager manager = o.getOWLOntologyManager();
			OWLDataFactory factory = manager.getOWLDataFactory();
			OWLNamedIndividual serviceActivityIndividual = factory.getOWLNamedIndividual(fullIri(serviceActivity));
			OWLNamedIndividual outcomeIndividual = null;
			if(outcome != null)
				outcomeIndividual = factory.getOWLNamedIndividual(fullIri(outcome));
			//Apply default default outcome only if the ServiceActivity isAccepted by the Assignee
			if(outcome == null && isAccepted == true)
			{
				OWLNamedIndividual activityTypeInd = individual(activityType);
				Set<OWLNamedIndividual> outcomes = reasoner().getObjectPropertyValues(
						activityTypeInd, objectProperty("legacy:hasDefaultOutcome")).getFlattened();
				if(outcomes.size() > 0)
					outcomeIndividual = outcomes.iterator().next();
			}
			updateActivity(serviceActivityIndividual, outcomeIndividual, details, assignedTo, modifiedBy, bo, messages);
		}
	}
	/**
	 * Updates the serviceActivity by setting auto default outcome if no outcome is set yet.
	 * @param serviceActivity
	 */
	public void updateActivityIfAutoDefaultOutcome(OWLNamedIndividual serviceActivity, BOntology bo, List<CirmMessage> messages) {
		OWLNamedIndividual existingOutcome = bo.getObjectProperty(serviceActivity, "legacy:hasOutcome");
		if (existingOutcome == null) {
    		OWLNamedIndividual activityType = objectProperty(serviceActivity, 
    				 "legacy:hasActivity",
    				 bo.getOntology());
    		if (activityType != null) {
    			OWLNamedIndividual autoDefaultOutcome = determineAutoDefaultOutcome(activityType);
    			if (autoDefaultOutcome != null) {
    				updateActivity(serviceActivity, autoDefaultOutcome, null, null, "auto", bo, messages);
    			}
    		}
		}
	}	

	/**
	 * Updates the passed in Business Ontology's ServiceActivity Axioms with the passed in parameter values.
	 * If any of the parameter values are null then that property is ignored.
	 * 
	 * @param serviceActivity : The ServiceActivity Individual to be updated 
	 * @param outcome : The Outcome Individual which is to be set as the Outcome of the ServiceActivity (can be null)
	 * @param details : Specified Details for the ServiceActivity by the user (can be null)
	 * @param assignedTo : The person's email/name/eNet No. to whom this ServiceActivity is assigned (can be null) 
	 * @param modifiedBy : The eNet No. of the User who sent in the request (can be null)
	 * @param bo : The Business Ontology of the Service Request
	 */
	public void updateActivity(OWLNamedIndividual serviceActivity, OWLNamedIndividual outcome, 
			String details, String assignedTo, String modifiedBy, BOntology bo, List<CirmMessage> messages)
	{
		boolean createMessageFromTemplate = false;
		try
		{
			OWLOntology o = bo.getOntology();
			OWLOntologyManager manager = o.getOWLOntologyManager();
			OWLDataFactory factory = manager.getOWLDataFactory();
			//06-20-2013 syed - Use the SR hasDateLastModified as the ServiceActivity hasUpdatedDate.
			OWLLiteral updatedDate = bo.getDataProperty("hasDateLastModified");
			//2016.11.05 hilpold - if sr was never updated, use now.
			if (updatedDate == null) {
				updatedDate = factory.getOWLLiteral(GenUtils.formatDate(new Date()), OWL2Datatype.XSD_DATE_TIME);
			}
			
			bo.deleteDataProperty(serviceActivity, dataProperty("legacy:hasUpdatedDate"));
			manager.addAxiom(o,
					factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("legacy:hasUpdatedDate"),serviceActivity, 
						updatedDate
					));
			if(details != null)
			{
				bo.deleteDataProperty(serviceActivity, dataProperty("legacy:hasDetails"));
				manager.addAxiom(o,factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("legacy:hasDetails")
						, serviceActivity, details));
			}
			if(modifiedBy != null)
			{
				bo.deleteDataProperty(serviceActivity, dataProperty("isModifiedBy"));
				manager.addAxiom(o,factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("isModifiedBy")
						, serviceActivity, modifiedBy));
			}
			if(outcome != null)
			{
				OWLNamedIndividual activityType = objectProperty(serviceActivity, 
															 "legacy:hasActivity",
															 bo.getOntology());
				if (activityType != null)
				{
					//HGDB fix. Might be impl; need HGDB for allProperties.
					activityType = OWL.individual(activityType.getIRI());
					bo.deleteObjectProperty(serviceActivity, objectProperty("legacy:hasOutcome"));
					manager.addAxiom(o,factory.getOWLObjectPropertyAssertionAxiom(
								 objectProperty("legacy:hasOutcome")
								, serviceActivity, outcome ));
					bo.deleteDataProperty(serviceActivity, dataProperty("legacy:hasCompletedTimestamp"));
					manager.addAxiom(o,
							factory.getOWLDataPropertyAssertionAxiom(
								dataProperty("legacy:hasCompletedTimestamp"),serviceActivity, 
								updatedDate
							));
					List<CirmMessage> localMessages = new ArrayList<CirmMessage>();
					checkOutcomeTrigger(serviceActivity, activityType, outcome, assignedTo, bo, localMessages);
					if (assignedTo == null) {
						if (hasAssignActivityToOutcomeEmail(activityType)) 
						{
							assignedTo = getAssignActivityToOutcomeEmail(outcome, bo);
							if (assignedTo != null)
							{ //hilpold assign here??? axiom
								createMessageFromTemplate = true;
							}							
						}
					}
					for (CirmMessage lm : localMessages) 
					{
						lm.addExplanation("updateActivity.checkoutcomeTrigger " + outcome.getIRI().getFragment()
								+ "Act: " + activityType.getIRI().getFragment());
						messages.add(lm);
					}
				}
			}
			//hilpold this has to happen after (!) hasAssignActivityToOutcomeEmail. 
			if(assignedTo != null)
			{
				bo.deleteDataProperty(serviceActivity, dataProperty("legacy:isAssignedTo"));
				manager.addAxiom(o,factory.getOWLDataPropertyAssertionAxiom(
						dataProperty("legacy:isAssignedTo")
						, serviceActivity, assignedTo));
			}
			//TODO: if time based activity then update time record.
			if (createMessageFromTemplate) 
			{
				OWLNamedIndividual activityType = objectProperty(serviceActivity, 
						 "legacy:hasActivity",
						 bo.getOntology());
				//HGDB error getting data properties for non HGDB individual activitytype; convert to hgdb:
				activityType = OWL.individual(activityType.getIRI());
				OWLNamedIndividual emailTemplate = objectProperty(activityType, "legacy:hasEmailTemplate");
				if(emailTemplate != null && USE_MESSAGE_MANAGER)
				{
					System.out.println("updateactivity & email: aType: " + activityType);
					CirmMimeMessage m = MessageManager.get().createMimeMessageFromTemplate(bo, dataProperty(activityType, "legacy:hasLegacyCode"), emailTemplate);
					if (m!= null) {
						m.addExplanation("updateActivity outcomeEmailAssign " + serviceActivity.getIRI().getFragment() 
								+ " AType: " + activityType 
								+ " Outcome: " + outcome
								+ " Tpl: " + emailTemplate.getIRI().getFragment());
						messages.add(m);
					}
					else
						System.err.println("ActivityManager: created Message was Null for " + (bo != null? bo.getObjectId() : bo) + " act:" + activityType + " tmpl: " + emailTemplate);
				}
				OWLNamedIndividual smsTemplate = objectProperty(activityType, "legacy:hasSmsTemplate");
				if(smsTemplate != null && USE_MESSAGE_MANAGER)
				{
					System.out.println("updateactivity & sms: aType: " + activityType);
					CirmSmsMessage m = MessageManager.get().createSmsMessageFromTemplate(bo, dataProperty(activityType, "legacy:hasLegacyCode"), smsTemplate);
					if (m!= null) {
						m.addExplanation("updateActivity outcomeSmsAssign " + serviceActivity.getIRI().getFragment() 
								+ " AType: " + activityType 
								+ " Outcome: " + outcome
								+ " Tpl: " + smsTemplate.getIRI().getFragment());
						messages.add(m);
					}
					else {
						System.err.println("ActivityManager: created SMS Message was Null for " + (bo != null? bo.getObjectId() : bo) + " act:" + activityType + " tmpl: " + smsTemplate);
					}
				} 

			}
		}catch (Exception e) {
			e.printStackTrace();
			if (THROW_ALL_EXC) 
				throw new RuntimeException(e);
		}
		
	}
	
	public void deleteActivity(OWLNamedIndividual serviceActivity, BOntology bo)
	{
		OWLOntology o = bo.getOntology();
		OWLOntologyManager manager = o.getOWLOntologyManager();
		OWLDataFactory factory = manager.getOWLDataFactory();
		manager.removeAxiom(o, factory.getOWLObjectPropertyAssertionAxiom(
						objectProperty("legacy:hasServiceActivity")
						, bo.getBusinessObject(), serviceActivity));
		manager.removeAxioms(o,o.getAxioms(serviceActivity));
	}
	
	private void checkOutcomeTrigger(OWLNamedIndividual serviceActivity, OWLNamedIndividual activityType, OWLNamedIndividual outcome, String assignedTo, BOntology bo , List<CirmMessage> messages)
	{
		//Create activityType triggers.
		triggerActivityAssignments(serviceActivity, activityType, outcome, assignedTo, bo, messages);
		//Create referral SRs.
		triggerReferralCaseOnOutcome(outcome, bo);
		//close service case on outcome
		triggerCloseCaseOnOutcome(serviceActivity, outcome, bo, messages);
		//Send email when status changes to X-Error
		triggerSendMessageOnOutCome(outcome, bo, messages);
	}
	
	/**
	 * Sends an email to WCS/Support group when an interface error is rejected by the department and the SR status is changed to X-Error
	 * @param outcome : The Outcome Individual which is to be set as the Outcome of the ServiceActivity (can be null)
	 * @param bo : The Business Ontology of the Service Request
	 * @param messages : 
	 */	
	private void triggerSendMessageOnOutCome(OWLNamedIndividual outcome,
			BOntology bo, List<CirmMessage> messages) {
		if (outcome  == null) return;
		// ensure HGDB individual if not yet in repo:
		outcome = OWL.individual(outcome.getIRI());
		System.out.println("Outcome:"+outcome);
		if (!reasoner().getInstances(
					OWL.and(OWL.oneOf(outcome),	OWL.some(OWL.objectProperty("legacy:hasLegacyEvent"), OWL.owlClass("legacy:SendEmail"))),true).isEmpty())		
		{
			OWLNamedIndividual outcomeEvent = OWL.objectProperty(outcome, "legacy:hasLegacyEvent");
			System.out.println("outcomeEvent:"+outcomeEvent);
			OWLNamedIndividual emailTemplate = objectProperty(outcomeEvent,"legacy:hasEmailTemplate");
			System.out.println("emailTemplate:"+emailTemplate);
			OWLNamedIndividual smsTemplate = objectProperty(outcomeEvent,"legacy:hasSmsTemplate");
			System.out.println("smsTemplate:" + smsTemplate);
			Set<OWLNamedIndividual> srType =   objectProperties(outcomeEvent,"legacy:hasServiceCase");
			System.out.println("srType:"+srType);
			OWLIndividual boSRType = individual(bo.getTypeIRI("legacy"));
			System.out.println("boSRType:"+boSRType);
			if (emailTemplate != null && USE_MESSAGE_MANAGER && srType.contains(boSRType)) {
				System.out.println("Sending Email:");
				CirmMimeMessage m = MessageManager.get().createMimeMessageFromTemplate(bo, dataProperty(outcomeEvent, "legacy:hasLegacyCode"),emailTemplate);
				System.out.println("CirmMimeMessage is : "+m);
				if (m != null) {
					m.addExplanation("triggerSendEmailOnOutCome " + outcome);
					messages.add(m);
				} else {
					System.err.println("ActivityManager: Message is NULL");
				}
			}
			else {
				System.out.println(boSRType+ " IS NOT AN INTERFACE SR TYPE: EMAIL WILL NOT BE SENT");
			}
			if (smsTemplate != null && USE_MESSAGE_MANAGER && srType.contains(boSRType)) {
				System.out.println("Sending SMS:");
				CirmSmsMessage m = MessageManager.get().createSmsMessageFromTemplate(bo, dataProperty(outcomeEvent, "legacy:hasLegacyCode"),smsTemplate);
				System.out.println("CirmSmsMessage is : "+ m);
				if (m != null) {
					m.addExplanation("triggerSendEmailOnOutCome " + outcome);
					messages.add(m);
				} else {
					System.err.println("ActivityManager: Message is NULL");
				}
			}
			else {
				System.out.println(boSRType+ " IS NOT AN INTERFACE SR TYPE: SMS WILL NOT BE SENT");
			}
		}
	}

	/**
	 * Closes a case using the activity completed date as status change date.
	 * @param serviceActivity
	 * @param outcome
	 * @param bo
	 */
	private void triggerCloseCaseOnOutcome(OWLNamedIndividual serviceActivity, OWLNamedIndividual outcome,
			BOntology bo, List<CirmMessage> messages)
	{
		if (serviceActivity == null) throw new IllegalArgumentException();
		if (outcome == null) throw new IllegalArgumentException();
		if (bo == null) throw new IllegalArgumentException();
		OWLNamedIndividual outcomeEvent = OWL.objectProperty(outcome, "legacy:hasLegacyEvent");
		if(outcomeEvent != null && outcomeEvent.getIRI().getFragment().equals("CloseServiceCase"))
		{
			OWLLiteral statusChangeModifiedOrCreatedBy;
			OWLNamedIndividual statusChangeStatus;
			OWLLiteral statusChangeDate;
			OWLDataFactory df = bo.getOntology().getOWLOntologyManager().getOWLDataFactory();
			
			// User / maybe department
			statusChangeModifiedOrCreatedBy = bo.getDataProperty(serviceActivity, "mdc:isModifiedBy");
			if(statusChangeModifiedOrCreatedBy == null) //the sr is new and not yet modified!
				statusChangeModifiedOrCreatedBy = bo.getDataProperty(serviceActivity, "mdc:isCreatedBy");
			// The new status
			statusChangeStatus = objectProperty(outcomeEvent, "legacy:hasStatus");
			// Use activtiy completion date as status change created, updated and completed date.
			statusChangeDate = bo.getDataProperty(serviceActivity, "legacy:hasCompletedTimestamp");
			
			// Validation
			if (statusChangeModifiedOrCreatedBy == null) {
				System.err.println("Error: triggerCloseCaseOnOutcome statusChangeModifiedOrCreatedBy could not be determined for act " + serviceActivity);
				System.err.println("Error: SR Number was: " + bo.getObjectId());
				statusChangeModifiedOrCreatedBy = df.getOWLLiteral(ACTIVITY_ERROR);
			}
			if (statusChangeStatus == null) {
				System.err.println("Error: triggerCloseCaseOnOutcome statusChangeStatus could not be determined for act " + serviceActivity.getIRI() + " and event " + outcomeEvent.getIRI());
				System.err.println("Error: SR Number was: " + bo.getObjectId());
				statusChangeStatus = df.getOWLNamedIndividual(fullIri("legacy:C-CLOSED")); 
			}
			if (statusChangeDate == null)
			{
				System.err.println("Error: triggerCloseCaseOnOutcome statusChangeDate could not be determined for act " + serviceActivity.getIRI() + " and event " + outcomeEvent.getIRI());
				System.err.println("Error: SR Number was: " + bo.getObjectId());
				System.err.println("Error: Using either SRs mdc:hasDateLastModified or mdc:hasDateCreated");
				statusChangeDate = bo.getDataProperty("mdc:hasDateLastModified");
				if (statusChangeDate == null) 
					statusChangeDate = bo.getDataProperty("mdc:hasDateCreated");
			}
			
			bo.deleteObjectProperty(bo.getBusinessObject(), "legacy:hasStatus");
			bo.addObjectProperty(bo.getBusinessObject(), "legacy:hasStatus", Json.object().set("iri", statusChangeStatus.getIRI().toString()));
			changeStatus(statusChangeStatus, GenUtils.parseDate(statusChangeDate), statusChangeModifiedOrCreatedBy.getLiteral(), bo, messages);
		}
	}

	private void triggerReferralCaseOnOutcome(OWLNamedIndividual outcome,
			BOntology bo)
	{
		OWLClassExpression q = and(owlClass("legacy:ServiceCaseOutcomeTrigger"),
				OWL.has(objectProperty("legacy:hasServiceCase"), individual(bo.getTypeIRI("legacy"))),
				OWL.has(objectProperty("legacy:hasOutcome"), individual(outcome.getIRI())),
				OWL.some(objectProperty("legacy:hasLegacyEvent"), owlClass("legacy:CreateServiceCase")));
		Set<OWLNamedIndividual> createCaseTriggers = reasoner().getInstances(q, false).getFlattened();
		for(OWLNamedIndividual trigger : createCaseTriggers)
		{
			Set<OWLNamedIndividual> events = reasoner().getObjectPropertyValues(trigger, objectProperty("legacy:hasLegacyEvent")).getFlattened();
			for(OWLNamedIndividual event: events)
			{
			
				OWLNamedIndividual srTypeToCreate = objectProperty(event ,"legacy:hasServiceCase");
				OWLNamedIndividual statusToSet = objectProperty(event ,"legacy:hasStatus");
				if(srTypeToCreate != null && statusToSet != null)
				{
					Json referrer = bo.toJSON();
					createReferralCase(Long.parseLong(bo.getObjectId()),referrer, OWL.owlClass(srTypeToCreate.getIRI()), statusToSet);
				}
			}
		}
	}

	private void triggerActivityAssignments(OWLNamedIndividual serviceActivity, OWLNamedIndividual activityType,
			OWLNamedIndividual outcome, String assignedTo, BOntology bo, List<CirmMessage> messages)
	{
		OWLClassExpression q = and(owlClass("legacy:ActivityTrigger"),
								OWL.has(objectProperty("legacy:hasActivity"), individual(activityType.getIRI())),
								OWL.has(objectProperty("legacy:hasOutcome"), individual(outcome.getIRI())),
								OWL.some(objectProperty("legacy:hasLegacyEvent"), owlClass("legacy:ActivityAssignment")));
		Set<OWLNamedIndividual> triggers = reasoner().getInstances(q, false).getFlattened();
		Date actCompletedDate;
		Date newActCreatedDate;
		try {
			actCompletedDate = GenUtils.parseDate(bo.getDataProperty(serviceActivity, "legacy:hasCompletedTimestamp"));
			if (actCompletedDate == null) throw new IllegalStateException("ServiceActivity must be completed and have a completed date.");
			//12-20-2013 tom/syed/boris Use the triggering activity's completed date as the new activities created date.
			newActCreatedDate = actCompletedDate;
		} catch (Exception e)
		{
			String msg = "triggerActivityAssignments could not determine dates for serviceActivity: " + serviceActivity.getIRI() 
					+ " Type: " + activityType.getIRI() 
					+ " Exc was: " + e.toString();
			throw new RuntimeException(msg, e);
		}
		for(OWLNamedIndividual trigger : triggers)
		{
			Set<OWLNamedIndividual> events = reasoner().getObjectPropertyValues(trigger, objectProperty("legacy:hasLegacyEvent")).getFlattened();
			for(OWLNamedIndividual event: events)
			{
				OWLNamedIndividual a = reasoner().getObjectPropertyValues(
							event,
							objectProperty("legacy:hasActivity"))
							.getFlattened().iterator().next();
				Set<OWLNamedIndividual> outcomes = reasoner().getObjectPropertyValues(
						event,
						objectProperty("legacy:hasOutcome"))
						.getFlattened();
				//01-07-2014 - removed per Liz's request.
//				if(outcomes.isEmpty())
//				{
//					//01-03-2014 Syed - If no outcome is configured for the event, then check the default outcome
//					//of the activity
//					outcomes = reasoner().getObjectPropertyValues(a, objectProperty("legacy:hasDefaultOutcome")).getFlattened();
//				}
				Set<OWLLiteral> businessCodes = reasoner().getDataPropertyValues(
						a, dataProperty("legacy:hasBusinessCodes"));
				boolean dupStaff = false;
				if(businessCodes.size() > 0)
				{
					dupStaff = businessCodes.iterator().next().getLiteral().contains("DUPSTAFF");
				}
				List<CirmMessage> localMessages = new ArrayList<CirmMessage>();
				if(outcomes.size() > 0)
					createActivity(a, outcomes.iterator().next(), null, (dupStaff) ? assignedTo : null, bo, newActCreatedDate, null, ACTIVITY_AUTO, localMessages);
				else
					createActivity(a, null, null, (dupStaff) ? assignedTo : null, bo, newActCreatedDate, null, ACTIVITY_AUTO, localMessages);
				for (CirmMessage lm : localMessages) 
				{
					lm.addExplanation("triggerActivityAssignments " + assignedTo 
							+ " Act: " + serviceActivity.getIRI().getFragment() 
							+ " ActType: " + activityType.getIRI().getFragment() 
							+ " Tri: " + trigger.getIRI().getFragment()
							+ " Eve: " + event.getIRI().getFragment());
					messages.add(lm);
				}
			}
		}
	}
	
	/**
	 * Creates a Referral Case 
	 * 
	 * @param referringCase
	 * @param owlClass - SR Type to 
	 * @param statusToSet
	 */
	
	private void createReferralCase(Long referringCaseId, Json referringCase, OWLClass srType,
			OWLNamedIndividual statusToSet)
	{
		LegacyEmulator emulator = new LegacyEmulator();
		Json newCase = Json.object("properties", Json.object());
		Json props = newCase.at("properties");
		newCase.set("type", "legacy:"+srType.getIRI().getFragment());
		GenUtils.timeStamp(props);
		props.set("legacy:hasStatus", 
				Json.object("iri", statusToSet.getIRI().toString()));
		props.set("legacy:hasParentCaseNumber", referringCaseId);
		Json rprops = referringCase.at("properties");
		if(rprops.has("atAddress"))
			props.set("atAddress",rprops.at("atAddress"));
		if(rprops.has("hasXCoordinate"))
			props.set("hasXCoordinate",rprops.at("hasXCoordinate"));
		if(rprops.has("hasYCoordinate"))
			props.set("hasYCoordinate",rprops.at("hasYCoordinate"));
		//TODO: not sure if this is needed.
		//expandIris(data);
		// remove properties that should be ignore or have been taken care of above already
		//validateAddresses(data); // we still need to go through actors' address etc.
		
		// set properties to the parent.
		if (rprops.has("hasPriority"))
			props.set("legacy:hasPriority", rprops.at("hasPriority"));
		if (rprops.has("hasIntakeMethod"))
			props.set("legacy:hasIntakeMethod", rprops.at("hasIntakeMethod"));
		Json result = emulator.saveNewServiceRequest(newCase.toString());
		try
		{
			Set<OWLNamedIndividual> interfaces = 
				    reasoner().getInstances(
						and(owlClass("legacy:LegacyInterface"), 
						    has(objectProperty("legacy:hasAllowableEvent"), individual("legacy:NEWSR")),
						    has(objectProperty("legacy:isLegacyInterface"), individual(newCase.at("type").asString()))), false).getFlattened();
			
			if (!interfaces.isEmpty())
			{
				OWLNamedIndividual LI = interfaces.iterator().next();							
				result.at("data").set("hasLegacyInterface", Json.object("hasLegacyCode", OWL.dataProperty(LI, "legacy:hasLegacyCode").getLiteral()));
			}
//			JMSClient.connectAndSend(LegacyMessageType.NewCase, 
//					((DBIDFactory) Refs.idFactory.resolve()).generateSequenceNumber(), result);
		}catch(Exception e)
		{
			System.err.println("Referral Case send to queue interface exception");
			e.printStackTrace(System.err);
			if (THROW_ALL_EXC) 
				throw new RuntimeException(e);
		}
	}

	/**
	 * Creates the serviceActivity axioms for the business object
	 * based on Fields and Answers that have been configured 
	 * to generate activities.
	 * @param bo
	 */
	private void createActivitiesFromQuestions(BOntology bo, List<CirmMessage> messages)
	{
		OWLOntology ontology = bo.getOntology();
		OWLNamedIndividual businessObject  =  bo.getBusinessObject();
		OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();

		Set<OWLIndividual> answers = businessObject.getObjectPropertyValues(factory.getOWLObjectProperty(fullIri("legacy:hasServiceAnswer")), ontology);
		for(OWLIndividual answer : answers)
		{
			Set<OWLIndividual> fields = answer.getObjectPropertyValues(factory.getOWLObjectProperty(fullIri("legacy:hasServiceField")), ontology);
			if(fields.isEmpty())
				continue;
			OWLNamedIndividual field = individual(fields.iterator().next().asOWLNamedIndividual().getIRI());
			List<CirmMessage> localMessages = new ArrayList<CirmMessage>();
			triggerActivityAssignmentsOnAnswer(field, answer, bo, localMessages);
			for (CirmMessage lm : localMessages) 
			{
				lm.addExplanation("createActivitiesFromQuestions F: " + field.getIRI().getFragment()
						+ "Ans: " + (answer.isNamed()? answer.asOWLNamedIndividual().getIRI().getFragment() : "anonymous"));
				messages.add(lm);
			}
		}
	}

	private void triggerActivityAssignmentsOnAnswer(
			OWLNamedIndividual field, OWLIndividual answer,
			BOntology bo, List<CirmMessage> messages)
	{
		OWLOntology ontology = bo.getOntology();
		OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
		Set<OWLNamedIndividual> triggers = reasoner().getObjectPropertyValues(field, objectProperty("legacy:hasActivityAssignment")).getFlattened();
		if(triggers.isEmpty())
			return;
		Set<OWLIndividual> answerObjects = answer.getObjectPropertyValues(factory.getOWLObjectProperty(fullIri("legacy:hasAnswerObject")), ontology);
		Set<OWLLiteral> answerValues = answer.getDataPropertyValues(factory.getOWLDataProperty(fullIri("legacy:hasAnswerValue")), ontology);
		
		for(OWLNamedIndividual trigger :triggers)
		{
			
			Set<OWLNamedIndividual> triggerAnswerObjects = reasoner().getObjectPropertyValues(trigger, objectProperty("legacy:hasAnswerObject")).getFlattened();
			Set<OWLLiteral> triggerAnswerValues = reasoner().getDataPropertyValues(trigger, dataProperty("legacy:hasAnswerValue"));
			
			for(OWLLiteral triggerAnswer : triggerAnswerValues)
			{
				if(answerValues.size() > 0)
				{
					String answerValue = answerValues.iterator().next().getLiteral();
					//When the hasAnswervalue in the ontology doesn't matter, it was given a value of "any"
					if(triggerAnswer.getLiteral().equalsIgnoreCase("any"))
					{
						createActivityFromAnswer(bo, messages, trigger, field, answerValue, null);
					}
					//TODO : The below commented "else if" can be modified and used for other requirements 
					//Saw another QuestionTrigger individual "1370732305" with hasAnswerValue
					// No idea what the requirement was, ie, Do we need to persist the hasAnswerValue
					// into any Activity field or not persist at all.
					//else if(triggerAnswer.getLiteral().equals(answerValue)) {
						//createActivityFromAnswer(bo, messages, trigger, field, answerValue, null);
					//}
				}

			}

			for(OWLNamedIndividual triggerAnswer: triggerAnswerObjects )
			if(answerObjects.contains(triggerAnswer))
			{
				createActivityFromAnswer(bo, messages, trigger, field, null, triggerAnswer);
			}
		}
	}
	
	private void createActivityFromAnswer(BOntology bo, List<CirmMessage> messages, 
			OWLNamedIndividual trigger, OWLNamedIndividual field, 
			String details, OWLNamedIndividual triggerAnswer) {
	
		Set<OWLNamedIndividual> events = reasoner().getObjectPropertyValues(trigger, objectProperty("legacy:hasLegacyEvent")).getFlattened();
		for(OWLNamedIndividual event: events)
		{
			Set<OWLNamedIndividual> activities = reasoner().getObjectPropertyValues(
					event,
					objectProperty("legacy:hasActivity"))
					.getFlattened();
			if(!activities.isEmpty())
			{
				OWLNamedIndividual a =	activities.iterator().next();
				Set<OWLNamedIndividual> outcomes = reasoner().getObjectPropertyValues(
						event,
						objectProperty("legacy:hasOutcome"))
						.getFlattened();
				List<CirmMessage>localMessages = new ArrayList<CirmMessage>();
				if(outcomes.size() > 0)
					createActivity(a, outcomes.iterator().next(), details, null, bo, null, null, null, localMessages);
				else
					createActivity(a, null, details, null, bo, null, null, null, localMessages);
				for (CirmMessage lm : localMessages) 
				{
					lm.addExplanation("triggerActivityAssignmentsOnAnswer F:" + field.getIRI().getFragment() + " A:" + triggerAnswer.getIRI().getFragment()
							+ " TriggerAns: " + triggerAnswer.getIRI().getFragment() 
							+ " Event: " + event.getIRI().getFragment() 
							+ " Activity: " + a.getIRI().getFragment());
					messages.add(lm);
				}
			}
		}

	}
	
	/**
	 * Registers a status change of an SR by creating a legacy:StatusChangeActivity.
	 */
	public void changeStatus(OWLNamedIndividual newStatus, Date statusChangeDate, String statusChangedBy, BOntology bo, List<CirmMessage> messages) {
		changeStatus(null, newStatus, statusChangeDate, statusChangedBy, bo, messages);
	}
	
	/**
	 * Registers a status change of an SR by creating a legacy:StatusChangeActivity.
	 * Adds the old status into the details field of the status change activity.
	 * @param oldStatus the status of the SR before the status change.
	 */
	public void changeStatus(OWLNamedIndividual oldStatus, OWLNamedIndividual newStatus, Date statusChangeDate, String statusChangedBy, BOntology bo, List<CirmMessage> messages)
	{
		OWLNamedIndividual statusChange = individual("legacy:StatusChangeActivity");
		String details = null;
		if (oldStatus != null) {
			String oldStatusFragment = oldStatus.getIRI().getFragment();
			details = oldStatusFragment == null? null : "Old: " + oldStatusFragment;
		}
		createActivity(statusChange, newStatus, details, null, bo, statusChangeDate, statusChangeDate, statusChangedBy, messages);
	}
	
	/**
	 * Set's the assign field of an activity based on an email found in the outcome label,
	 * if the serviceActivity is an autoAssign activity and the activityType has an OutcomeEmailAssignmentRule 
	 * and an outcome was selected that contains an email address
	 * 
	 * @param bo
	 * @param activity
	 */
	private String getAssignActivityToOutcomeEmail(OWLNamedIndividual outcome, BOntology bo)
	{
		if (outcome == null) return null; //throw exc later
		if (bo == null) return null; //throw exc later
		// Check if activityType has a rule that assigns an outcome email to the activity's assigned to field
		String result = null;
		//1. Get Outcome and outcome label
		//2. check if outcome label contains email
		//3. return email address only
		String outcomeLabel = "NULL";
		if (outcome != null)
		{
			outcomeLabel = OWL.getEntityLabel(outcome);
			if (outcomeLabel != null && outcomeLabel.contains("@")) 
			{
				result = GenUtils.findEmailIn(outcomeLabel);
			}
		}
		System.out.println("AssignActivityToOutcomeEmail for outcome: " + outcome + " olabel: " + outcomeLabel);
		return result;
	}
	
	/**
	 * For activity types with templates and an AssignActivityToOutcomeEmail rule,
	 * emails should be sent only after update, not on creation of the activity unless there is a default outcome. 
	 * @param activityType
	 * @param bo
	 * @return
	 */
	private boolean hasAssignActivityToOutcomeEmail(OWLNamedIndividual activityType)
	{
		Set<OWLNamedIndividual> assignRules = OWL.objectProperties(activityType, 
				"legacy:hasAssignmentRule"); 
		for (OWLNamedIndividual rule : assignRules)
		{
			if (Model.legacy("AssignActivityToOutcomeEmail").equals(rule.getIRI()))
				return true;
		}
		return false;
	}

	/**
	 * Determines the configured suspense days for an activity type.
	 * 
	 * @param activityType
	 * @return days or 0 if not configured or parse error.
	 */
	private float determineSuspenseDays(OWLNamedIndividual activityType) {
		float result = 0;
		Set<OWLLiteral> suspenseDays = reasoner().getDataPropertyValues(
				activityType,
				dataProperty("legacy:hasSuspenseDays"));
		if (suspenseDays.size() > 0) {
			try	{
				result = suspenseDays.iterator().next().parseFloat();
			} catch (Exception e) {
				result = 0;
				System.err.println("Error: Could not parse suspense day float value for " + activityType);
			}
		}
		return result;
	}
	
	/**
	 * Determines the configured occur day value for an activity type.
	 * @param activityType
	 * @return days or 0 if not configured or error.
	 */
	private float determineOccurDays(OWLNamedIndividual activityType) {
		float result = 0;
		Set<OWLLiteral> occurDays = reasoner().getDataPropertyValues(
				activityType,
				dataProperty("legacy:hasOccurDays"));
		if(occurDays.size() > 0) {
			try {
				result = occurDays.iterator().next().parseFloat();
			} catch (Exception e) {
				result = 0;
				System.err.println("ActivityManager: " + activityType + " parseFloat problem - Delayed activity creation failed!");
				if (THROW_ALL_EXC) 
					throw new RuntimeException(e);
			}
		}
		return result;
	}
	
	/**
	 * Schedules Activity Creation in OccurDays from now.
	 * @param bo
	 * @param activityType
	 * @param occurBaseDate typically now unless configured and user provided
	 * @param delayDays
	 * @param useWorkWeek
	 * @param details
	 * @param isAssignedTo
	 */
	private void scheduleActivityCreationOccurDays(BOntology bo, OWLNamedIndividual activityType, Date occurBaseDate, float occurDays, boolean useWorkWeek, String details, String isAssignedTo) {
		Date delayedCreationDate = calculateScheduledDelayDate(occurBaseDate, occurDays, useWorkWeek);
		try
		{
			String serverUrl = getServerUrl();
			if(serverUrl != null)
			{	
					String path =  "/legacy/bo/"+ bo.getObjectId() + "/activities/create/"+ activityType.getIRI().getFragment();
					String fullUrl = serverUrl + path; 
					if (USE_TIME_MACHINE) 
					{
						//Json post = Json.object();
						Json post = null;
						//if(details != null)
						//	post.set("legacy:hasDetails", details);
						//if(isAssignedTo != null)
						//	post.set("legacy:isAssignedTo", isAssignedTo);										
						String taskId = getNextTimeMachineTaskIDFor(path);
						if (DBG) System.out.println("ActManager: TM task " + taskId);
						Calendar delayedDateCal = Calendar.getInstance();
						delayedDateCal.setTime(delayedCreationDate);
						Json j = GenUtils.timeTask(taskId, delayedDateCal, fullUrl, post);
						if (j.is("ok", false)) {
							throw new RuntimeException("Time machine post returned false");
						}
						String msg = "Scheduled create  " + activityType.getIRI().getFragment() + " 5dayWW: " + useWorkWeek + " " 
								+ occurBaseDate + " + " + occurDays + " = " + delayedCreationDate;
						ThreadLocalStopwatch.now(msg);
					}
			} 
			else
			{
				System.err.println("ActivityManager: " + activityType + " Server URL was NULL - Delayed activity creation failed! bo: " + bo.getObjectId());
			}
		}catch(Exception e)
		{
			System.out.println("Could not addTimer for activityType " + activityType.getIRI());
			if(DBG)
				e.printStackTrace(System.err);
			if (THROW_ALL_EXC) 
				throw new RuntimeException(e);
		}						

	}
	
	/**
	 * Schedules creation of an Overdue Activity at a specified due date.
	 * @param bo
	 * @param overdueActivityType
	 * @param due
	 * @param activity activity for which this overdue activity should be created
	 * @param activityType type of activity for which this overdue activity should be created
	 */
	private void scheduleOverdueActivityCreationAtDueDate(BOntology bo, OWLNamedIndividual overdueActivityType, Calendar due, OWLNamedIndividual activity, OWLNamedIndividual activityType) {
		try
		{
			String serverUrl = getServerUrl();
			if(serverUrl != null)
			{	
				String path = "/legacy/bo/"+ bo.getObjectId()
						+ "/activity/"+ activity.getIRI().getFragment() 
						+ "/overdue/create/" + overdueActivityType.getIRI().getFragment();
				String fullUrl = serverUrl + path;
				if (USE_TIME_MACHINE) 
				{
					//cannot use task, as serviceActivity will get new id on each retry. oa is type and therefore constant across retries.
					String almostTaskId = bo.getObjectId() + "act: " + activityType.getIRI().getFragment() + "/overdue/create/" + overdueActivityType.getIRI().getFragment();  
					String taskId = getNextTimeMachineTaskIDFor(almostTaskId);
					if (DBG) System.out.println("ActManager: TM task " + taskId);
					Json j = GenUtils.timeTask(taskId, due, fullUrl, null);
					if (j.is("ok", false))
						throw new RuntimeException("Time machine post returned false");
				}
			}
		} catch(Exception e)
		{
			ThreadLocalStopwatch.error("Could not addTimer for serviceActivity" + activity.getIRI().toString());
			if(DBG)
				e.printStackTrace(System.out);
			if (THROW_ALL_EXC) 
				throw new RuntimeException(e);
		}
	}

	/**
	 * Calculates the scheduled date by adding daysToAdd to now and optionally using workweek.<br>
	 * <br>
	 * In test mode this will time lapse days to minutes for workflow testing.<br>
	 * <br>
	 * @param now
	 * @param daysToAdd days to add to now (in test mode, minutes will be added)
	 * @param useWorkWeek skip sat/sun and holidays.
	 * @return
	 */
	private Date calculateScheduledDelayDate(Date now, float daysToAdd, boolean useWorkWeek) {
		if (StartUp.isProductionMode()) {
			return OWL.addDaysToDate(now, daysToAdd, useWorkWeek);
		} else {
			return calculateTimeLapseDelayDateForTest(now, daysToAdd);
		}
	}
	
	/**
	 * Converts daysToAdd to minutes (min 1 to max 10) and calculates a future date for workflow testing purposes.<br>
	 * e.g. 6.5 days would be converted to 6.5 minutes.<br>
	 * e.g. 90 days will be converted to 9 minutes.<br>
	 * e.g. 0.5 days will be converted to 1 minute.<br>
	 * <br>
	 * @param now
	 * @param daysToAdd days to convert to minutes 
	 * @return
	 * @throws IllegalStateException if unintentionally called in 311Hub production mode.
	 */
	private Date calculateTimeLapseDelayDateForTest(Date now, float daysToAdd) {	
		ThreadLocalStopwatch.now("TEST MODE: IGNORING PROVIDED BASE DATE " + now);
		now = new Date();
		if (StartUp.isProductionMode()) throw new IllegalStateException("Illegal use of test time lapse calculation in production");
		//use minutes instead of days
		float minutesToAddMax10 = daysToAdd;
		while (minutesToAddMax10 > 10) {
			minutesToAddMax10 = minutesToAddMax10 / 10.0f;
		}
		if (minutesToAddMax10 < 1) {
			minutesToAddMax10 = 1;
		}
		long milliSecondsToAdd = Math.round(minutesToAddMax10 * 60.0 * 1000.0);
		Date result = new Date(now.getTime() + milliSecondsToAdd);
		ThreadLocalStopwatch.now("TEST MODE: CALCULATED DATE " + result + " current time is " + now);
		return result;
	}
	
	/**
	 * Gets the due base date by looking up if legacy:hasUserProvidedDueBaseDate is available in activityType or SR type.
	 * If ServiceAnswer in BO has valid date & it is configured for the activity or SR type, it is returned.
	 * If not configured, or not parseable, now is returned.
	 * 
	 * @param bo
	 * @param activityType
	 * @return
	 */
	private Date determineDueBaseDate(BOntology bo, OWLNamedIndividual activityType, Date defaultDate) {
		Date result = defaultDate;
		//Determine type
		OWLNamedIndividual serviceRequestType = OWL.individual("legacy:" + bo.getTypeIRI().getFragment());
		Set<OWLNamedIndividual> dateServiceQuestions = OWL.objectProperties(serviceRequestType, "legacy:hasUserProvidedDueBaseDate");
		if (!dateServiceQuestions.isEmpty()) {
			OWLNamedIndividual dateServiceQuestion = dateServiceQuestions.iterator().next();
			OWLLiteral dataType = OWL.dataProperty(dateServiceQuestion, "legacy:hasDataType");
			if ("DATE".equals(dataType.getLiteral())) {
				Date userDate = findServiceAnswerDateForQuestion(bo, dateServiceQuestion);
				if (userDate != null) {
					result = userDate;
				}
			}
		}
		return result;
	}
	
	/**
	 * Returns the default outcome for the activityType, if legacy:isAutoDefaultOutcome true and
	 * hasDefaultOutcome is available. Null is returned otherwise.
	 * 
	 * @param bo
	 * @return
	 */
	private OWLNamedIndividual determineAutoDefaultOutcome(OWLNamedIndividual activityType) {
		OWLNamedIndividual result = null;
		if (isAutoDefaultOutcomeTrue(activityType)) {
			Set<OWLNamedIndividual> defaultOutcomeSet = OWL.objectProperties(activityType, "legacy:hasDefaultOutcome");
			if (!defaultOutcomeSet.isEmpty()) {
				result = defaultOutcomeSet.iterator().next();
			}
		}
		return result;
	}
	
	
	/**
	 * Determines if legacy:isAutoDefaultOutcome true is configured for an activity type.
	 * 
	 * @param activityType
	 * @return
	 */
	private boolean isAutoDefaultOutcomeTrue(OWLNamedIndividual activityType) {
		Set<OWLLiteral> booleanLiterals = OWL.dataProperties(activityType, "legacy:isAutoDefaultOutcome");
		if (!booleanLiterals.isEmpty()) {
			OWLLiteral booleanLiteral = booleanLiterals.iterator().next(); 
			return (booleanLiteral.isBoolean() && booleanLiteral.parseBoolean());
		} else {
			return false;
		}		
	}
	
	private static OWLDataProperty hasAnswerValueDP = OWL.dataProperty(Model.legacy("hasAnswerValue"));  
	
	/**
	 * Finds answer date to serviceQuestion in Bo, if available.
	 *  
	 * @param bo
	 * @param serviceQuestion
	 * @return parsedDate or null if invalid or not available.
	 */
	private Date findServiceAnswerDateForQuestion(BOntology bo, OWLNamedIndividual serviceQuestion) {
		Date result = null;
		OWLOntology o = bo.getOntology();
		Set<OWLAxiom> referencingAxioms = o.getReferencingAxioms(serviceQuestion, false);
		Iterator<OWLAxiom> it = referencingAxioms.iterator();
		while (result == null && it.hasNext()) {
			//Find axioms where serviceQuestion is Object
			OWLAxiom cur = it.next();
			if (cur instanceof OWLObjectPropertyAssertionAxiom) {
				OWLObjectPropertyAssertionAxiom opa = (OWLObjectPropertyAssertionAxiom) cur;
				if (serviceQuestion.equals(opa.getObject())) {
					OWLIndividual answerCandidate = opa.getSubject();
					Set<OWLLiteral> dateCandidates = answerCandidate.getDataPropertyValues(hasAnswerValueDP, o);
					Iterator<OWLLiteral> itDateCand = dateCandidates.iterator();
					while (result == null && itDateCand.hasNext()) {
						//Find parseable date
						OWLLiteral dateCandidate = itDateCand.next();
						if (dateCandidate.getLiteral() != null && !dateCandidate.getLiteral().isEmpty())
						try {
							result = GenUtils.parseDate(dateCandidate);
						} catch (Exception e) {
							result = null;
						}
					} //inner while
				}
			}
		} //outer while
		return result;
	}
}

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
package org.sharegov.cirm.rest;

import static mjson.Json.array;
import static mjson.Json.object;
import static org.sharegov.cirm.OWL.individual;
import static org.sharegov.cirm.utils.GenUtils.ko;
import static org.sharegov.cirm.utils.GenUtils.ok;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import mjson.Json;

import org.hypergraphdb.HGPersistentHandle;
import org.hypergraphdb.app.owl.HGDBOntology;
import org.hypergraphdb.app.owl.versioning.Revision;
import org.hypergraphdb.app.owl.versioning.RevisionID;
import org.hypergraphdb.app.owl.versioning.VersionedOntology;
import org.hypergraphdb.app.owl.versioning.VersionedOntologyComparator.RevisionCompareOutcome;
import org.hypergraphdb.app.owl.versioning.VersionedOntologyComparator.RevisionComparisonResult;
import org.hypergraphdb.app.owl.versioning.VersionedOntologyComparator.VersionedOntologyComparisonResult;
import org.hypergraphdb.app.owl.versioning.distributed.ClientCentralizedOntology;
import org.hypergraphdb.app.owl.versioning.distributed.DistributedOntology;
import org.hypergraphdb.app.owl.versioning.distributed.VDHGDBOntologyRepository;
import org.hypergraphdb.app.owl.versioning.distributed.activity.PullActivity;
import org.hypergraphdb.app.owl.versioning.distributed.activity.PushActivity;
import org.hypergraphdb.peer.HGPeerIdentity;
import org.hypergraphdb.peer.workflow.ActivityResult;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.sharegov.cirm.OWL;
import org.sharegov.cirm.OntologyChangesRepo;
import org.sharegov.cirm.Refs;
import org.sharegov.cirm.StartUp;
import org.sharegov.cirm.event.EventDispatcher;
import org.sharegov.cirm.owl.CachedReasoner;
import org.sharegov.cirm.owl.SynchronizedOWLOntologyManager;
import org.sharegov.cirm.utils.GenUtils;
import org.sharegov.cirm.utils.OntologyCommit;

import uk.ac.manchester.cs.owl.owlapi.OWLOntologyManagerImpl;

//import com.clarkparsia.pellet.owlapiv3.PelletReasoner;

@Path("ontadmin")
@Produces("application/json")
public class OntoAdmin extends RestService
{
	public enum REPOACTION {PULL, REVERT, NOTHING};
	
	public final String CACHED_REASONER_RESDIR = "/src/resources/cachedReasoner/";
	public final String CACHED_REASONER_POPULATE_GET_INSTANCES_CACHE_FILE = CACHED_REASONER_RESDIR + "populateGetInstancesCache.json";
	public static int ACTIVITY_TIMEOUT_SECS = 180;
	
	
	protected VDHGDBOntologyRepository repo()
	{
		return Refs.owlRepo.resolve().repo();
	}
	
	private void notifyOntoChange(String ontologyIri)
	{
		Set<OWLNamedIndividual> S = 
			OWL.reasoner().getInstances(OWL.hasData("hasOntologyIRI", OWL.literal(ontologyIri)), false).getFlattened();
		if (!S.isEmpty())
			EventDispatcher.get().dispatch(S.iterator().next(), individual("BO_Update"));		
	}
	
	@GET
	@Path("/list")
	public Json listOntologies()
	{
		VDHGDBOntologyRepository repo = repo();
		Json A = array();
		for (HGDBOntology O : repo.getOntologies())
		{
			Json x = object()
				.set("iri", O.getOntologyID().getOntologyIRI().toString())
				.set("versionIRI", O.getOntologyID().getVersionIRI() == null ?
						null : O.getOntologyID().getVersionIRI().toString())
				.set("documentIRI", O.getDocumentIRI() == null ?
						null : O.getDocumentIRI().toString())
				.set("imports", array());
			for (OWLOntology io : O.getImports())
				x.at("imports").add(io.getOntologyID().getOntologyIRI().toString());
			A.add(x);
		}
		return A;
	}
	
	@GET
	@Path("/listAll")
	public Json listAllOntologies()
	{
		Json A = array();
		for (OWLOntology O : OWL.manager().getOntologies())
		{
			Json x = object()
				.set("iri", O.getOntologyID().getOntologyIRI().toString())
				.set("versionIRI", O.getOntologyID().getVersionIRI() == null ?
						null : O.getOntologyID().getVersionIRI().toString())
				.set("documentIRI", OWL.manager().getOntologyDocumentIRI(O).toString())
				.set("imports", array());
			for (OWLOntology io : O.getImports())
				x.at("imports").add(io.getOntologyID().getOntologyIRI().toString());
			A.add(x);
		}
		return A;
	}

	@GET
	@Path("/currentVersion/{iri}")
	public Json getCurrentVersion(@PathParam("iri") String iri)
	{
		VDHGDBOntologyRepository repo = repo();
		try
		{		
			OWLOntology O = OWL.manager().getOntology(IRI.create(iri)); 
			if (O == null)
				return ko("Ontology not found: " + iri);
			repo.printAllOntologies();
			VersionedOntology vo = repo.getVersionControlledOntology(O);
			return ok().set("version", vo.getHeadRevision().getRevision())
					   .set("comment", vo.getHeadRevision().getRevisionComment())
					   .set("timestamp", vo.getHeadRevision().getTimeStamp().getTime())
					   .set("user", vo.getHeadRevision().getUser());
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.err);
			return ko(t.toString());
		}
	}

	@POST
	@Path("/revert/{iri}")
	public Json revert(@PathParam("iri") String iri)
	{
		VDHGDBOntologyRepository repo = repo();
		try
		{
			Refs.owlRepo.resolve().ensurePeerStarted();
			OWLOntology O = OWL.manager().getOntology(IRI.create(iri)); 
			if (O == null)
				return ko("Ontology not found: " + iri);
			VersionedOntology vo = repo.getVersionControlledOntology(O);
			if (vo.getNrOfRevisions() <= 1)
				return ko("Already at first revision.");
			vo.revertHeadTo(vo.getRevisions().get(vo.getNrOfRevisions() - 2));
			OWL.reasoner().flush();
			notifyOntoChange(iri);
			return ok();
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.err);
			return ko(t.toString());
		}
	}

	@POST
	@Path("/synchTo/{iri}/{version}")
	public Json pullTo(@PathParam("iri") String iri, @PathParam("version") String version)
	{
		try
		{
			Refs.owlRepo.resolve().ensurePeerStarted();
			OWLOntology O = OWL.manager().getOntology(IRI.create(iri)); 
			if (O == null)
				return ko("Ontology not found: " + iri);			
			OWL.reasoner().flush();
			notifyOntoChange(iri);			
			return ko("Operation not supported yet.");
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.err);
			return ko(t.toString());
		}		
	}
	
//	@SuppressWarnings("unchecked")
//	private PelletReasoner pellet(OWLReasoner r)
//	{
//		if ( r instanceof Wrapper<?>) {
//			r = ((Wrapper<OWLReasoner>)r).unwrapAll();
//		}
//		if (r instanceof PelletReasoner)
//			return (PelletReasoner)r;
//		else
//			throw new IllegalArgumentException("Pellet reasoner expected.");
//	}
	
	@POST
	@Path("/synchToLatest/{iri}")
	public Json pull(@PathParam("iri") String iri)
	{
		VDHGDBOntologyRepository repo = repo();
		try
		{
			Refs.owlRepo.resolve().ensurePeerStarted();
			OWLOntology O = OWL.manager().getOntology(IRI.create(iri)); 
			if (O == null)
				return ko("Ontology not found: " + iri);
			DistributedOntology vo = repo.getDistributedOntology(O); 
			PullActivity pull = repo.pull(vo, Refs.owlRepo.resolve().getDefaultPeer());
			pull.getFuture().get();
			OWL.reasoner().flush();
			notifyOntoChange(iri);		
			return ok().set("message", pull.getCompletedMessage());
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.err);
			return ko(t.toString());
		}		
	}
	
	@POST
	@Path("/pushLatest/{iri}")
	public Json push(@PathParam("iri") String iri)
	{
		VDHGDBOntologyRepository repo = repo();
		try
		{ 
			Refs.owlRepo.resolve().ensurePeerStarted();
//			OWLOntology O = OWL.manager().getOntology(IRI.create(iri)); 
//			if (O == null)
//				return ko("Ontology not found: " + iri);
			String messages = "";
			for (OWLOntology o : OWL.ontologies()) {
				VersionedOntology vo = repo.getVersionControlledOntology(o);
				DistributedOntology dOnto = repo.getDistributedOntology(o);
				HGPeerIdentity server = Refs.owlRepo.resolve().getDefaultPeer();
				
				switch (getBeforeCommitPushAction(dOnto, server)) {
					case REVERT:
						int lastMatchingRevision = revertToLastMatch (vo, dOnto, server);
						pullFromserver(dOnto, server);
						applyChangesSinceRevision (o, lastMatchingRevision);
						break;
					case PULL:
						pullFromserver(dOnto, server);							
						break;
					case NOTHING:
						break;

					default:
						throw new RuntimeException ("Cannot commit this time.");
				}			
				
				PushActivity push = repo.push(dOnto, server);
				push.getFuture().get();
				messages += push.getCompletedMessage() + ", ";
			}
			return ok().set("message", messages); 
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.err);
			return ko(t.toString());
		}		
	}
	
	protected boolean commit(String userName, String comment, List <OWLOntologyChange> changes) throws RuntimeException
	{		
		VDHGDBOntologyRepository repo = repo();
		try
		{ 
			Refs.owlRepo.resolve().ensurePeerStarted();
			
			OWLOntologyManager manager = OWL.manager();	
			manager.applyChanges(changes);			

			int committedOntologyCount = 0;
			for (OWLOntology o : OWL.ontologies()) {
				VersionedOntology vo = repo.getVersionControlledOntology(o);
				//vo.revertHeadTo();
				if (vo == null) {
					throw new RuntimeException ("Ontology found, but not versioned: " + o.getOntologyID());
				}
				
				int nrOfCommittableChanges = vo.getNrOfCommittableChanges(); 
				if (nrOfCommittableChanges == 0) {
					int conflicts = vo.getWorkingSetConflicts().size(); 
					if (conflicts > 0) {
						throw new RuntimeException ("All " + conflicts + " pending changes in Ontology " + o.getOntologyID() + " are conflicts, " 
								+ "which will be removed automatically on commit, so there is no single change to commit..");			
					} else {
						//do nothing
					}
				} else {
					DistributedOntology dOnto = repo.getDistributedOntology(o); 
					HGPeerIdentity server = Refs.owlRepo.resolve().getDefaultPeer();
					
					switch (getBeforeCommitPushAction(dOnto, server)) {
						case REVERT:
							int lastMatchingRevision = revertToLastMatch (vo, dOnto, server);
							pullFromserver(dOnto, server);
							applyChangesSinceRevision (o, lastMatchingRevision);
							manager.applyChanges(changes);	
							break;
						case PULL:
							pullFromserver(dOnto, server);							
							break;
						case NOTHING:
							break;
	
						default:
							throw new RuntimeException ("Cannot commit this time.");
					}
					
					vo.commit(userName, comment);
					committedOntologyCount++;
					int revision = vo.getHeadRevision().getRevision();
					OntologyChangesRepo.getInstance().setOntoRevisionChanges(o.getOntologyID().toString(), revision, userName, comment, changes);
				}
			}
			
			return committedOntologyCount > 0;
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.err);
			throw new RuntimeException(t.toString());
		}				
	}
	
	private int revertToLastMatch (VersionedOntology vo, DistributedOntology dOnto, HGPeerIdentity server){
		VersionedOntologyComparisonResult compare = null;
		try {
			compare = repo().compareOntologyToRemote(dOnto, server, ACTIVITY_TIMEOUT_SECS);
		} catch (Throwable t) {
			throw new RuntimeException("System error while comparing to remote");
		}
		int result = compare.getRevisionResults().get(compare.getLastMatchingRevisionIndex()).getTarget().getRevision();
		vo.revertHeadTo(new RevisionID((HGPersistentHandle) dOnto, result));
		
		return result;
	}
	
	private void applyChangesSinceRevision (OWLOntology o, int revision){
		Map<Integer, OntologyCommit> changes = OntologyChangesRepo.getInstance().getAllRevisionChangesForOnto(o.getOntologyID().toString());
		
		VersionedOntology vo = repo().getVersionControlledOntology(o);
		
		if (vo == null) {
			throw new RuntimeException ("Ontology found, but not versioned: " + o.getOntologyID());
		}
		
		for (Map.Entry<Integer, OntologyCommit> rx: changes.entrySet()){
			if (rx.getKey().intValue() > revision){
				OWL.manager().applyChanges(rx.getValue().getChanges());
				vo.commit(rx.getValue().getUserName(), rx.getValue().getComment());
				int newRevision = vo.getHeadRevision().getRevision();
				OntologyChangesRepo.getInstance().deleteOntoRevisionChanges(o.getOntologyID().toString(), rx.getKey().intValue());
				OntologyChangesRepo.getInstance().setOntoRevisionChanges(o.getOntologyID().toString(), newRevision, rx.getValue());
			}
		}
	}

	
	@POST
	@Path("/reloadFileBased/{iri}")
	public Json reload(@PathParam("iri") String iri)
	{
		try
		{
			OWLOntologyManager manager = OWL.manager(); 
			OWLOntology ont = manager.getOntology(IRI.create(iri)); 
			if (ont == null)
				return ko("Ontology not found: " + iri);
			IRI ontologyDocumentIRI = OWL.manager().getOntologyDocumentIRI(ont);
//
			System.out.print("loading " + iri + " from " + ontologyDocumentIRI + "...");
			synchronized(OWL.reasoner()) 
			{
		        manager.removeOntology(ont);
		        try {
		            ont = manager.loadOntologyFromOntologyDocument(ontologyDocumentIRI);
					System.out.println("done.");
					System.out.print("flushing reasoner" + OWL.reasoner() + "...");
					//TODO maybe check OWL.reasoner().getBufferingMode(), so no flush might be needed.
					OWL.reasoner().flush();
					System.out.println("done.");
				}
		        catch (Throwable t) {
		        	if (manager instanceof SynchronizedOWLOntologyManager) 
		        	{
		        		manager = ((SynchronizedOWLOntologyManager)manager).getWrappedOWLOntologyManager(); 
		        	}
		            ((OWLOntologyManagerImpl) manager).ontologyCreated(ont);  // put it back - a hack but it works
		            manager.setOntologyDocumentIRI(ont, ontologyDocumentIRI);
		            String msg = "reload of " + iri + " from " + ontologyDocumentIRI + " failed with exception " + t.toString();
		            System.err.println(msg);
		            return ko(msg);
		        }			
			}
			//
			notifyOntoChange(iri);			
			return ok().set("message", "reload successful for " + iri + " from " + ontologyDocumentIRI);
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.err);
			return ko(t.toString());
		}		
	}

	/**
	 * Gets the total number of cache entries in the cached reasoner. 
	 * @return a json containing a long value; -1 if not a cached reasoner.
	 */
	@GET
	@Path("/cachedReasonerTotalEntryCount")
	public Json cachedReasonerTotalEntryCount()
	{
		OWLReasoner r = OWL.reasoner();
		if (r instanceof CachedReasoner) 
		{
			CachedReasoner cr = (CachedReasoner)r;
			return Json.make(cr.getTotalCacheEntryCount());
		}
		else
			return Json.make(-1);
	}

	@GET
	@Path("/cachedReasonerStats")
	public Json cachedReasonerStats()
	{
		OWLReasoner r = OWL.reasoner();
		if (r instanceof CachedReasoner) 
		{
			CachedReasoner cr = (CachedReasoner)r;
			return Json.make(cr.getCacheStatus());
		}
		else
			return Json.make("Reasoner is not a CachedReasoner instance.");
	}
	
	@GET
	@Path("/nrOfCachedReasoners")
	public Json nrOfCachedReasoners()
	{
		return Json.object("nrOfCachedReasoners", OWL.loader().getNrOfCachedReasoners());
	}
	

	@GET
	@Path("/cachedReasonerQ1")
	public Json cachedReasonerQ1()
	{
		OWLReasoner r = OWL.reasoner();
		if (r instanceof CachedReasoner) 
		{
			CachedReasoner cr = (CachedReasoner)r;
			return cr.getInstancesCacheRequests();
		}
		else
			return Json.make("Reasoner is not a CachedReasoner instance.");
	}

	@GET
	@Path("/cachedReasonerQ1Populate")
	public Json cachedReasonerQ1Populate()
	{
		String fileStr = StartUp.config.at("workingDir").asString() + CACHED_REASONER_POPULATE_GET_INSTANCES_CACHE_FILE;
		File f = new File(fileStr);
		if (!f.exists()) 
			return GenUtils.ko("Cannot populate: no file at :" + CACHED_REASONER_POPULATE_GET_INSTANCES_CACHE_FILE);
		OWLReasoner r = OWL.reasoner();
		if (r instanceof CachedReasoner) 
		{
			Json queries = Json.read(GenUtils.readTextFile(f));
			CachedReasoner cr = (CachedReasoner)r;
			return cr.populateGetInstancesCache(queries);
		}
		else
			return Json.make("Reasoner is not a CachedReasoner instance.");
	}

	
	public synchronized Json compare(String ontologyName){
		Refs.owlRepo.resolve().ensurePeerStarted();
		VDHGDBOntologyRepository repo = repo();
	//			VDHGDBOntologyRepository repo = owlRepo.repo();

		Json json = Json.array(); 
	    String iri = null;
		
	
		iri = "hgdb://www.miamidade.gov/cirm/" + ontologyName; 
		
		HGDBOntology activeOnto = repo().getOntologyByDocumentIRI(IRI.create(iri));
		//HGDBOntology activeOnto = repo.getOntologyByDocumentIRI(OWL.fullIri("http://www.miamidade.gov/cirm/legacy"));
					
		DistributedOntology distributedOnto = repo.getDistributedOntology(activeOnto); 
		
		
		HGPeerIdentity server;  
		ClientCentralizedOntology centralO = (ClientCentralizedOntology)distributedOnto; 
		
		
		server = centralO.getServerPeer(); 
		
		//if(server == null || distributedOnto == null || repo == null)
		//	return json;
		
		VersionedOntologyComparisonResult result = repo.compareOntologyToRemote(distributedOnto, server, 180); 
		
		Revision source = null; 
		Revision target = null;
		
		for(RevisionComparisonResult r : result.getRevisionResults()){
			
			RevisionCompareOutcome revisionOutcome = r.getOutcome();
		    
			source = r.getSource(); 
			target = r.getTarget(); 
			
			if(!revisionOutcome.name().equals("MATCH"))
			{
			Json outcome = Json.object().set("name", revisionOutcome.name());
		    
			Json sourceJson = Json.object();
			if(source != null)
			{
			
				sourceJson.set("revision", source.getRevision())
			    		.set("comment", source.getRevisionComment())
			    		.set("date", source.getTimeStamp().getTime())
			    		.set("user", source.getUser());	
			}
			else
			sourceJson = Json.nil();
		  
			Json targetJson = Json.object();
		    if(target != null){
		    
		    	targetJson.set("revision", target.getRevision())
			    		.set("comment", target.getRevisionComment())
			    		.set("date", target.getTimeStamp().getTime())
			    		.set("user", target.getUser());	
		    }
		    else
		    	targetJson = Json.nil();
			
			
			Json obj = Json.object().set("outcome", outcome)
					.set("source", sourceJson)
					.set("target", targetJson);			
			
			
			
			json.add(obj);
			}
		}		
		
		return json;
	}
	
	
	public static void main(String[]args)
	{
		OntoAdmin admin = new OntoAdmin();		
	
		try
		{
			Refs.owlRepo.resolve().ensurePeerStarted();
			Thread.sleep(10000);
			for (HGPeerIdentity id : admin.repo().getPeer().getConnectedPeers())
			{
				System.out.println(id.toString() + " -- " + admin.repo().getPeer().getNetworkTarget(id));
				System.out.println("dev:" + admin.repo().getPeer().getIdentity("cirmdevelopmentontology"));
			}
		}
		catch (Throwable t)
		{
			t.printStackTrace();
		}
		finally
		{
			admin.repo().getPeer().stop();
		}
	}
	
	/**
	 * Checks if local pending changes may be committed and sent to the server based on a comparison.
	 * Will open explanatory dialogs if a reason is found that would prevent a commit.
	 * 
	 * @param dOnto
	 * @param server
	 * @return
	 */
	public REPOACTION getBeforeCommitPushAction(DistributedOntology dOnto, HGPeerIdentity server) {
		VersionedOntologyComparisonResult result = null;
		try {
			result = repo().compareOntologyToRemote(dOnto, server, ACTIVITY_TIMEOUT_SECS);
		} catch (Throwable t) {
			throw new RuntimeException("System error while comparing to remote");
		}
		if (result != null) {
			if (result.isConflict()) {
				return REPOACTION.REVERT;
			} else if (result.isTargetNewer()) {
				return REPOACTION.PULL;							
			} else {
				return REPOACTION.NOTHING;
			}
		} else {
			throw new RuntimeException("Cannot commit: There was a problem comparing the local history to the server's ontology. This might mean that the server was not available or a timeout occured. ");
		}
	}
	
	/**
	 * pull from repo
	 * 
	 */
	
	public boolean pullFromserver (DistributedOntology dOnto, HGPeerIdentity server){
		PullActivity pa = repo().pull(dOnto, server);
		try {
			ActivityResult paa = pa.getFuture().get(ACTIVITY_TIMEOUT_SECS, TimeUnit.SECONDS);
			if (paa.getException() != null) {						
				throw new RuntimeException(paa.getException().getMessage());
			}
		} catch (Throwable e) {
			throw new RuntimeException("Transaction timed out while pulling from the server."); 	
		}
		return true;
	}

}

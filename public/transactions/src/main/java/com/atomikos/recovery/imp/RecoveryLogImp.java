package com.atomikos.recovery.imp;

import java.util.Collection;
import java.util.HashSet;

import com.atomikos.icatch.TxState;
import com.atomikos.logging.Logger;
import com.atomikos.logging.LoggerFactory;
import com.atomikos.recovery.AdminLog;
import com.atomikos.recovery.CoordinatorLogEntry;
import com.atomikos.recovery.CoordinatorLogEntryRepository;
import com.atomikos.recovery.LogException;
import com.atomikos.recovery.LogReadException;
import com.atomikos.recovery.ParticipantLogEntry;
import com.atomikos.recovery.RecoveryLog;

public class RecoveryLogImp implements RecoveryLog, AdminLog {

	private static final Logger LOGGER = LoggerFactory.createLogger(RecoveryLogImp.class);
	
	private CoordinatorLogEntryRepository repository;

	public void setRepository(CoordinatorLogEntryRepository repository) {
		this.repository = repository;
	}

	@Override
	public void terminated(ParticipantLogEntry entry)  {
		try {
			CoordinatorLogEntry coordinatorLogEntry =null;
			coordinatorLogEntry = repository.get(entry.id);
			if (coordinatorLogEntry == null) {
				LOGGER.logWarning("termination called on non existent Coordinator "+ entry.id + " " + entry.uri);
			} else {	
				CoordinatorLogEntry updated = coordinatorLogEntry.terminated(entry);
				repository.put(updated.id, updated);
				if (coordinatorLogEntry.superiorCoordinatorId!=null) {
					CoordinatorLogEntry parentCoordinatorLogEntry = repository.get(coordinatorLogEntry.superiorCoordinatorId);
					if (parentCoordinatorLogEntry!=null) {
						CoordinatorLogEntry parentUpdated = parentCoordinatorLogEntry.terminated(new ParticipantLogEntry(coordinatorLogEntry.superiorCoordinatorId, coordinatorLogEntry.id, coordinatorLogEntry.expires(), "", coordinatorLogEntry.getResultingState()));
						repository.put(parentUpdated.id, parentUpdated);	//TODO deal with disk full -> pending parent
					}
				}
			}
		} catch (LogException e) {
			LOGGER.logWarning("Unable to write to repository: "+entry+" - leaving cleanup to recovery housekeeping...", e);
		} catch (IllegalArgumentException e) {
			LOGGER.logWarning("Unexpected error while terminating participant entry - ignoring (may result in orphaned log entry)", e);
		} 
	}

	

	@Override
	public void terminatedWithHeuristicRollback(ParticipantLogEntry entry) throws LogException {

		CoordinatorLogEntry coordinatorLogEntry = repository.get(entry.id);
		if (coordinatorLogEntry == null) {
			LOGGER.logWarning("terminatedWithHeuristicRollback called on non existent Coordinator "
					+ entry.id + " " + entry.uri);
		} else {
			CoordinatorLogEntry updated = coordinatorLogEntry.terminatedWithHeuristicRollback(entry);
			repository.put(updated.id, updated);
		}
	}

	@Override
	public Collection<ParticipantLogEntry> getCommittingParticipants()
			throws LogReadException {
		Collection<ParticipantLogEntry> committingParticipants = new HashSet<ParticipantLogEntry>();
		Collection<CoordinatorLogEntry> committingCoordinatorLogEntries = repository.findAllCommittingCoordinatorLogEntries();
		
		for (CoordinatorLogEntry coordinatorLogEntry : committingCoordinatorLogEntries) {
			for (ParticipantLogEntry participantLogEntry : coordinatorLogEntry.participants) {
				committingParticipants.add(participantLogEntry);
			}
		}
		return committingParticipants;
	}

	private void write(CoordinatorLogEntry coordinatorLogEntry)
			throws IllegalStateException, LogException {
		if (!entryAllowed(coordinatorLogEntry)) {
			throw new IllegalStateException();
		}
		repository.put(coordinatorLogEntry.id, coordinatorLogEntry);
	}

	private boolean entryAllowed(CoordinatorLogEntry coordinatorLogEntry) throws LogReadException {
		CoordinatorLogEntry existing = repository
				.get(coordinatorLogEntry.id);
		return coordinatorLogEntry.transitionAllowedFrom(existing);
	}


	@Override
	public void presumedAborting(ParticipantLogEntry entry)
			throws IllegalStateException, LogException {
		if (entry == null || entry.state != TxState.IN_DOUBT) {
			throw new IllegalArgumentException();
		}
		
		CoordinatorLogEntry coordinatorLogEntry = repository.get(entry.id);
		if (coordinatorLogEntry == null) { 
			//first time this participant is found in resource: write IN_DOUBT entry in log to do presumed abort on next scan
			//this gives any concurrent OLTP 2-phase commit a reasonable delay to proceed without interference of recovery
			coordinatorLogEntry = createCoordinatorLogEntry(entry);
			write(coordinatorLogEntry);
			throw new IllegalStateException();
		} else if (coordinatorLogEntry.superiorCoordinatorId != null) {
			
			CoordinatorLogEntry parentCoordinatorLogEntry =	repository.get(coordinatorLogEntry.superiorCoordinatorId );
			if(parentCoordinatorLogEntry!=null && parentCoordinatorLogEntry.getResultingState() == TxState.IN_DOUBT){
				throw new IllegalStateException();
			}
			
		} else {
			CoordinatorLogEntry updated = coordinatorLogEntry.presumedAborting(entry);
			
			write(updated);
		}
	}

	private CoordinatorLogEntry createCoordinatorLogEntry(
			ParticipantLogEntry entry) {
		CoordinatorLogEntry coordinatorLogEntry;
		ParticipantLogEntry[] participantDetails = new ParticipantLogEntry[1];
		participantDetails[0] = entry;
		coordinatorLogEntry = new CoordinatorLogEntry(entry.id,
				participantDetails);
		return coordinatorLogEntry;
	}

	@Override
	public void terminatedWithHeuristicCommit(ParticipantLogEntry entry) throws LogException {
		CoordinatorLogEntry coordinatorLogEntry = repository
				.get(entry.id);
		if (coordinatorLogEntry == null) {
			LOGGER.logWarning("terminatedWithHeuristicCommit called on non existent Coordinator "
					+ entry.id + " " + entry.uri);
		} else {
			CoordinatorLogEntry updated = coordinatorLogEntry.terminatedWithHeuristicCommit(entry);
			repository.put(updated.id, updated);
			
		}

	}

	@Override
	public void terminatedWithHeuristicHazard(ParticipantLogEntry entry) {
		LOGGER.logWarning("terminatedWithHeuristicHazard " + entry);
	}

	@Override
	public void terminatedWithHeuristicMixed(ParticipantLogEntry entry) throws LogException {
		CoordinatorLogEntry coordinatorLogEntry = repository
				.get(entry.id);
		if (coordinatorLogEntry == null) {
			LOGGER.logWarning("terminatedWithHeuristicMixed called on non existent Coordinator "
					+ entry.id + " " + entry.uri);
		} else {

			CoordinatorLogEntry updated = coordinatorLogEntry.terminatedWithHeuristicMixed(entry);
			repository.put(updated.id, updated);
			
		}
	}

	@Override
	public CoordinatorLogEntry[] getCoordinatorLogEntries() {
		try {
			Collection<CoordinatorLogEntry> allCoordinatorLogEntries= repository.getAllCoordinatorLogEntries();
			return allCoordinatorLogEntries.toArray(new CoordinatorLogEntry[allCoordinatorLogEntries.size()]);
		} catch (LogReadException e) {
			LOGGER.logWarning("Could not retrieve coordinators - returning empty array", e);	
		}
		
		return new CoordinatorLogEntry[0];
	}

	@Override
	public void remove(String coordinatorId) {
		CoordinatorLogEntry toRemove = null;
		try {
			 toRemove =	repository.get(coordinatorId);
		} catch (LogReadException e) {
			LOGGER.logWarning("Could not retrieve coordinator to remove: "+coordinatorId+" - ignoring", e);	
		}
		if (toRemove != null) {
			toRemove = toRemove.markAsTerminated();
			try {
				repository.put(coordinatorId, toRemove);
			} catch (Exception e) {
				LOGGER.logWarning("Could not remove coordinator: "+coordinatorId+" - ignoring", e);
			} 
		}
		
	}

}

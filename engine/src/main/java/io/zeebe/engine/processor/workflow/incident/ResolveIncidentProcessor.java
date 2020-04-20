/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.incident;

import io.zeebe.engine.processor.NoopResponseWriter;
import io.zeebe.engine.processor.SideEffectProducer;
import io.zeebe.engine.processor.TypedRecord;
import io.zeebe.engine.processor.TypedRecordProcessor;
import io.zeebe.engine.processor.TypedResponseWriter;
import io.zeebe.engine.processor.TypedStreamWriter;
import io.zeebe.engine.processor.workflow.BpmnStepProcessor;
import io.zeebe.engine.processor.workflow.SideEffectQueue;
import io.zeebe.engine.processor.workflow.job.JobErrorThrownProcessor;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.instance.IncidentState;
import io.zeebe.engine.state.instance.IndexedRecord;
import io.zeebe.engine.state.instance.JobState;
import io.zeebe.engine.state.instance.JobState.State;
import io.zeebe.protocol.impl.record.value.incident.IncidentRecord;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.intent.Intent;
import java.util.function.Consumer;

public final class ResolveIncidentProcessor implements TypedRecordProcessor<IncidentRecord> {

  public static final String NO_INCIDENT_FOUND_MSG =
      "Expected to resolve incident with key '%d', but no such incident was found";

  private final SideEffectQueue queue = new SideEffectQueue();
  private final TypedResponseWriter noopResponseWriter = new NoopResponseWriter();

  private final ZeebeState zeebeState;
  private final BpmnStepProcessor stepProcessor;
  private final JobErrorThrownProcessor jobErrorThrownProcessor;

  public ResolveIncidentProcessor(
      final ZeebeState zeebeState,
      final BpmnStepProcessor stepProcessor,
      final JobErrorThrownProcessor jobErrorThrownProcessor) {
    this.stepProcessor = stepProcessor;
    this.zeebeState = zeebeState;
    this.jobErrorThrownProcessor = jobErrorThrownProcessor;
  }

  @Override
  public void processRecord(
      final TypedRecord<IncidentRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {
    final long incidentKey = command.getKey();
    final IncidentState incidentState = zeebeState.getIncidentState();

    final IncidentRecord incidentRecord = incidentState.getIncidentRecord(incidentKey);
    if (incidentRecord != null) {
      incidentState.deleteIncident(incidentKey);

      streamWriter.appendFollowUpEvent(incidentKey, IncidentIntent.RESOLVED, incidentRecord);
      responseWriter.writeEventOnCommand(
          incidentKey, IncidentIntent.RESOLVED, incidentRecord, command);

      // workflow / job is already cleared if canceled, then we simply delete without resolving
      attemptToResolveIncident(responseWriter, streamWriter, sideEffect, incidentRecord);
    } else {
      rejectResolveCommand(command, responseWriter, streamWriter, incidentKey);
    }
  }

  private void rejectResolveCommand(
      final TypedRecord<IncidentRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final long incidentKey) {
    final String errorMessage = String.format(NO_INCIDENT_FOUND_MSG, incidentKey);

    streamWriter.appendRejection(command, RejectionType.NOT_FOUND, errorMessage);
    responseWriter.writeRejectionOnCommand(command, RejectionType.NOT_FOUND, errorMessage);
  }

  private void attemptToResolveIncident(
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect,
      final IncidentRecord incidentRecord) {
    final long jobKey = incidentRecord.getJobKey();
    final boolean isJobIncident = jobKey > 0;

    if (isJobIncident) {
      attemptToSolveJobIncident(jobKey, streamWriter);
    } else {
      attemptToContinueWorkflowProcessing(responseWriter, streamWriter, sideEffect, incidentRecord);
    }
  }

  private void attemptToContinueWorkflowProcessing(
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect,
      final IncidentRecord incidentRecord) {
    final long elementInstanceKey = incidentRecord.getElementInstanceKey();
    final IndexedRecord failedRecord =
        zeebeState.getWorkflowState().getElementInstanceState().getFailedRecord(elementInstanceKey);

    if (failedRecord != null) {

      queue.clear();
      queue.add(responseWriter::flush);
      stepProcessor.processRecordValue(
          createRecord(failedRecord),
          failedRecord.getKey(),
          failedRecord.getValue(),
          failedRecord.getState(),
          streamWriter,
          noopResponseWriter,
          queue::add);

      sideEffect.accept(queue);
    }
  }

  // TODO (saig0): need to pass the record properties for the new BPMN element processor
  private TypedRecord<WorkflowInstanceRecord> createRecord(final IndexedRecord failedRecord) {
    return new TypedRecord<>() {

      @Override
      public String toJson() {
        return null;
      }

      @Override
      public long getPosition() {
        return 0;
      }

      @Override
      public long getSourceRecordPosition() {
        return 0;
      }

      @Override
      public long getTimestamp() {
        return 0;
      }

      @Override
      public Intent getIntent() {
        return failedRecord.getState();
      }

      @Override
      public int getPartitionId() {
        return 0;
      }

      @Override
      public RecordType getRecordType() {
        return null;
      }

      @Override
      public RejectionType getRejectionType() {
        return null;
      }

      @Override
      public String getRejectionReason() {
        return null;
      }

      @Override
      public ValueType getValueType() {
        return null;
      }

      @Override
      public long getKey() {
        return failedRecord.getKey();
      }

      @Override
      public WorkflowInstanceRecord getValue() {
        return failedRecord.getValue();
      }

      @Override
      public int getRequestStreamId() {
        return 0;
      }

      @Override
      public long getRequestId() {
        return 0;
      }

      @Override
      public long getLength() {
        return 0;
      }

      @Override
      public Record<WorkflowInstanceRecord> clone() {
        return this;
      }
    };
  }

  private void attemptToSolveJobIncident(final long jobKey, final TypedStreamWriter streamWriter) {
    final JobState jobState = zeebeState.getJobState();
    final JobRecord job = jobState.getJob(jobKey);
    final JobState.State state = jobState.getState(jobKey);

    if (state == State.FAILED) {
      // make job activatable again
      jobState.resolve(jobKey, job);

    } else if (state == State.ERROR_THROWN) {
      // try to throw the error again
      jobErrorThrownProcessor.processRecord(jobKey, job, streamWriter);
    }
  }
}

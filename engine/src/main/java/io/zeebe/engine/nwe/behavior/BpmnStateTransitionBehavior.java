/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.nwe.behavior;

import io.zeebe.engine.metrics.WorkflowEngineMetrics;
import io.zeebe.engine.nwe.BpmnElementContainerProcessor;
import io.zeebe.engine.nwe.BpmnElementContext;
import io.zeebe.engine.processor.KeyGenerator;
import io.zeebe.engine.processor.TypedStreamWriter;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableFlowElement;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableFlowNode;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableSequenceFlow;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.instance.EventTrigger;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import java.util.function.Function;

public final class BpmnStateTransitionBehavior {

  private final TypedStreamWriter streamWriter;
  private final KeyGenerator keyGenerator;
  private final BpmnStateBehavior stateBehavior;
  private final Function<BpmnElementType, BpmnElementContainerProcessor<ExecutableFlowElement>>
      processorLookUp;

  private final WorkflowEngineMetrics metrics;

  public BpmnStateTransitionBehavior(
      final TypedStreamWriter streamWriter,
      final KeyGenerator keyGenerator,
      final BpmnStateBehavior stateBehavior,
      final WorkflowEngineMetrics metrics,
      final Function<BpmnElementType, BpmnElementContainerProcessor<ExecutableFlowElement>>
          processorLookUp) {
    this.streamWriter = streamWriter;
    this.keyGenerator = keyGenerator;
    this.stateBehavior = stateBehavior;
    this.metrics = metrics;
    this.processorLookUp = processorLookUp;
  }

  public void transitionToActivated(final BpmnElementContext context) {
    if (context.getIntent() != WorkflowInstanceIntent.ELEMENT_ACTIVATING) {
      throw new IllegalStateTransitionException(WorkflowInstanceIntent.ELEMENT_ACTIVATED, context);
    }

    transitionTo(context, WorkflowInstanceIntent.ELEMENT_ACTIVATED);

    // TODO (saig0): update state because of the step guards
    stateBehavior.updateElementInstance(
        context,
        elementInstance -> elementInstance.setState(WorkflowInstanceIntent.ELEMENT_ACTIVATED));

    metrics.elementInstanceActivated(context.getBpmnElementType());
  }

  public void transitionToTerminating(final BpmnElementContext context) {
    if (context.getIntent() != WorkflowInstanceIntent.ELEMENT_ACTIVATING
        && context.getIntent() != WorkflowInstanceIntent.ELEMENT_ACTIVATED
        && context.getIntent() != WorkflowInstanceIntent.ELEMENT_COMPLETING
        && context.getIntent() != WorkflowInstanceIntent.EVENT_OCCURRED) {
      throw new IllegalStateTransitionException(
          WorkflowInstanceIntent.ELEMENT_TERMINATING, context);
    }

    transitionTo(context, WorkflowInstanceIntent.ELEMENT_TERMINATING);

    stateBehavior.updateElementInstance(
        context,
        elementInstance -> elementInstance.setState(WorkflowInstanceIntent.ELEMENT_TERMINATING));
  }

  public void transitionToTerminated(final BpmnElementContext context) {
    if (context.getIntent() != WorkflowInstanceIntent.ELEMENT_TERMINATING) {
      throw new IllegalStateTransitionException(WorkflowInstanceIntent.ELEMENT_TERMINATED, context);
    }

    transitionTo(context, WorkflowInstanceIntent.ELEMENT_TERMINATED);

    stateBehavior.updateElementInstance(
        context,
        elementInstance -> elementInstance.setState(WorkflowInstanceIntent.ELEMENT_TERMINATED));

    metrics.elementInstanceTerminated(context.getBpmnElementType());
  }

  public void transitionToCompleting(final BpmnElementContext context) {
    if (context.getIntent() != WorkflowInstanceIntent.ELEMENT_ACTIVATED) {
      throw new IllegalStateTransitionException(WorkflowInstanceIntent.ELEMENT_COMPLETING, context);
    }

    transitionTo(context, WorkflowInstanceIntent.ELEMENT_COMPLETING);

    stateBehavior.updateElementInstance(
        context,
        elementInstance -> elementInstance.setState(WorkflowInstanceIntent.ELEMENT_COMPLETING));
  }

  public void transitionToCompleted(final BpmnElementContext context) {
    if (context.getIntent() != WorkflowInstanceIntent.ELEMENT_COMPLETING) {
      throw new IllegalStateTransitionException(WorkflowInstanceIntent.ELEMENT_COMPLETED, context);
    }

    transitionTo(context, WorkflowInstanceIntent.ELEMENT_COMPLETED);

    stateBehavior.updateElementInstance(
        context,
        elementInstance -> elementInstance.setState(WorkflowInstanceIntent.ELEMENT_COMPLETED));

    metrics.elementInstanceCompleted(context.getBpmnElementType());
  }

  private void transitionTo(final BpmnElementContext context, final WorkflowInstanceIntent intent) {
    streamWriter.appendFollowUpEvent(
        context.getElementInstanceKey(), intent, context.getRecordValue());
  }

  public void takeSequenceFlow(
      final BpmnElementContext context, final ExecutableSequenceFlow sequenceFlow) {
    if (context.getIntent() != WorkflowInstanceIntent.ELEMENT_COMPLETED) {
      throw new IllegalStateTransitionException(
          WorkflowInstanceIntent.SEQUENCE_FLOW_TAKEN, context);
    }

    final var record =
        context
            .getRecordValue()
            .setElementId(sequenceFlow.getId())
            .setBpmnElementType(sequenceFlow.getElementType());

    streamWriter.appendNewEvent(
        keyGenerator.nextKey(), WorkflowInstanceIntent.SEQUENCE_FLOW_TAKEN, record);

    stateBehavior.spawnToken(context);
  }

  public ElementInstance activateChildInstance(
      final BpmnElementContext context, final ExecutableFlowElement childElement) {

    final var childInstanceRecord =
        context
            .getRecordValue()
            .setFlowScopeKey(context.getElementInstanceKey())
            .setElementId(childElement.getId())
            .setBpmnElementType(childElement.getElementType());

    final var childInstanceKey = keyGenerator.nextKey();

    streamWriter.appendNewEvent(
        childInstanceKey, WorkflowInstanceIntent.ELEMENT_ACTIVATING, childInstanceRecord);

    return stateBehavior.createChildElementInstance(context, childInstanceKey, childInstanceRecord);
  }

  // TODO (saig0): move to event related behavior?
  public long activateBoundaryInstance(
      final BpmnElementContext context,
      final WorkflowInstanceRecord record,
      final EventTrigger event) {

    final var boundaryInstanceKey = keyGenerator.nextKey();
    streamWriter.appendNewEvent(
        boundaryInstanceKey, WorkflowInstanceIntent.ELEMENT_ACTIVATING, record);
    stateBehavior.createBoundaryInstance(context, boundaryInstanceKey, record);

    stateBehavior.spawnToken(context);

    return boundaryInstanceKey;
  }

  public <T extends ExecutableFlowNode> void takeOutgoingSequenceFlows(
      final T element, final BpmnElementContext context) {

    final var outgoingSequenceFlows = element.getOutgoing();
    if (outgoingSequenceFlows.isEmpty()) {
      // behaves like an implicit end event
      onCompleted(element, context);

    } else {
      outgoingSequenceFlows.forEach(sequenceFlow -> takeSequenceFlow(context, sequenceFlow));
    }
  }

  public void onCompleted(
      final ExecutableFlowElement element, final BpmnElementContext childContext) {

    final var flowScope = element.getFlowScope();
    final var flowScopeProcessor = processorLookUp.apply(flowScope.getElementType());
    final var flowScopeContext = stateBehavior.getFlowScopeContext(childContext);

    flowScopeProcessor.onChildCompleted(flowScope, flowScopeContext, childContext);
  }

  public void onTerminated(
      final ExecutableFlowElement element, final BpmnElementContext childContext) {

    final var flowScope = element.getFlowScope();
    final var flowScopeProcessor = processorLookUp.apply(flowScope.getElementType());
    final var flowScopeContext = stateBehavior.getFlowScopeContext(childContext);

    flowScopeProcessor.onChildTerminated(flowScope, flowScopeContext, childContext);
  }

  private static final class IllegalStateTransitionException extends IllegalStateException {

    private static final String MESSAGE =
        "Expected to take transition to '%s' but element instance is in state '%s'. [context: %s]";

    private IllegalStateTransitionException(
        final WorkflowInstanceIntent transition, final BpmnElementContext context) {
      super(String.format(MESSAGE, transition, context.getIntent(), context));
    }
  }
}
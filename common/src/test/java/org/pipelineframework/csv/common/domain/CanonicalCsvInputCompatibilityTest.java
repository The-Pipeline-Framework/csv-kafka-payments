package org.pipelineframework.csv.common.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pipelineframework.csv.domain.PipelineDomainProtoAdapters;
import org.pipelineframework.csv.grpc.PipelineTypes;

class CanonicalCsvInputCompatibilityTest {

  @Test
  void priorJsonAdmissionWithoutContentReferenceRemainsAbsent() {
    var codec = new org.pipelineframework.orchestrator.JsonTransitionPayloadCodec();
    var payload = new org.pipelineframework.orchestrator.SerializedTransitionPayload(
        org.pipelineframework.csv.domain.CsvPaymentsInputFile.class.getName(),
        org.pipelineframework.orchestrator.JsonTransitionPayloadCodec.ENCODING,
        "{\"filepath\":\"/inputs/payments.csv\",\"csvFolderPath\":\"/inputs\",\"sourceIdentity\":\"legacy-snapshot\"}");
    var decoded = (org.pipelineframework.csv.domain.CsvPaymentsInputFile) codec.decode(payload);
    assertTrue(decoded.contentRef().isAbsent());
    assertEquals(decoded, codec.decode(codec.encode(decoded)));
  }

  @Test
  void connectorReferenceSurvivesDurableJsonSnapshot() {
    var origin = new org.pipelineframework.connector.ConnectorPayloadOrigin(
        org.pipelineframework.connector.ConnectorBindingName.of("csv-input-s3"),
        new org.pipelineframework.connector.ConnectorOperationIdentity(
            org.pipelineframework.connector.ConnectorProviderId.of("s3.objects"), "s3",
            org.pipelineframework.connector.ConnectorOperationKind.OBJECT_SOURCE, 1),
        1, java.util.Optional.empty());
    var reference = new org.pipelineframework.repository.PayloadReference("s3", "inputs",
        "payments.csv", "text/csv", "raw", "etag-one", 12L, "version-one",
        java.util.Map.of(), java.util.Optional.of(origin));
    var input = new org.pipelineframework.csv.domain.CsvPaymentsInputFile(
        java.nio.file.Path.of("s3", "inputs", "payments.csv"),
        java.nio.file.Path.of("s3", "inputs"), "etag-one",
        org.pipelineframework.type.CanonicalFieldValue.of(reference));
    var codec = new org.pipelineframework.orchestrator.JsonTransitionPayloadCodec();
    var snapshot = new org.pipelineframework.orchestrator.ExecutionInputSnapshot(
        org.pipelineframework.orchestrator.ExecutionInputShape.UNI, input);
    var decoded = (org.pipelineframework.orchestrator.ExecutionInputSnapshot)
        codec.decode(codec.encode(snapshot));
    assertEquals(input, decoded.payload());
  }

  @Test
  void priorInputWireWithoutContentReferenceRemainsAbsentAfterRoundTrip() {
    var priorWire = PipelineTypes.CsvPaymentsInputFile.newBuilder()
        .setFilepath("/inputs/payments.csv")
        .setCsvFolderPath("/inputs")
        .setSourceIdentity("legacy-snapshot")
        .build();

    var decoded = PipelineDomainProtoAdapters.fromProto(priorWire);

    assertTrue(decoded.contentRef().isAbsent());
    assertFalse(PipelineDomainProtoAdapters.toProto(decoded).hasContentRef());
  }
}

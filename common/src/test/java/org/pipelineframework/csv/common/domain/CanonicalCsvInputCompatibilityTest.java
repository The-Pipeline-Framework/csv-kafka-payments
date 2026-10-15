package org.pipelineframework.csv.common.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pipelineframework.csv.domain.PipelineDomainProtoAdapters;
import org.pipelineframework.csv.grpc.PipelineTypes;

class CanonicalCsvInputCompatibilityTest {

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

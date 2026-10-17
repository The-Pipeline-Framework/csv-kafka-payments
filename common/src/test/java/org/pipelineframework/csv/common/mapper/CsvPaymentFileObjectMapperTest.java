package org.pipelineframework.csv.common.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.pipelineframework.connector.ConnectorBindingName;
import org.pipelineframework.connector.ConnectorOperationIdentity;
import org.pipelineframework.connector.ConnectorOperationKind;
import org.pipelineframework.connector.ConnectorPayloadOrigin;
import org.pipelineframework.connector.ConnectorProviderId;
import org.pipelineframework.objectingest.ObjectSnapshot;
import org.pipelineframework.repository.PayloadReference;

class CsvPaymentFileObjectMapperTest {
  private static final String KEY = "incoming/payments-123.csv";
  private final CsvPaymentFileObjectMapper mapper = new CsvPaymentFileObjectMapper();

  @Test
  void mapsBindingOwnedS3ReferenceWithoutLocalPath() {
    var input = mapper.map(snapshot(KEY, reference(KEY, true)));

    assertEquals(Path.of("/s3", "csv-payments-input", KEY), input.filepath());
    assertEquals(Path.of("/s3", "csv-payments-input", "incoming"), input.csvFolderPath());
    assertEquals("etag-one", input.sourceIdentity());
    assertTrue(input.contentRef().asOptional().isPresent());
  }

  @Test
  void preservesS3IdentityAndOwnedReferenceAcrossCanonicalAndRestJson() throws Exception {
    var reference = reference(KEY, true);
    var input = mapper.map(snapshot(KEY, reference));
    var json = org.pipelineframework.config.pipeline.PipelineJson.mapper();
    var durableRestored = json.readValue(json.writeValueAsBytes(input),
        org.pipelineframework.csv.domain.CsvPaymentsInputFile.class);
    // The REST boundary uses Jackson's standard Path URI codec, unlike PipelineJson's module.
    var restJson = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var restoredPath = restJson.readValue(restJson.writeValueAsBytes(durableRestored.filepath()), Path.class);
    var restoredFolder = restJson.readValue(restJson.writeValueAsBytes(durableRestored.csvFolderPath()), Path.class);
    var restored = new org.pipelineframework.csv.domain.CsvPaymentsInputFile(
        restoredPath, restoredFolder, durableRestored.sourceIdentity(), durableRestored.contentRef());

    assertEquals(input, durableRestored);
    assertTrue(input.filepath().isAbsolute());
    assertEquals(input.filepath(), restored.filepath());
    assertEquals(input.csvFolderPath(), restored.csvFolderPath());
    assertEquals(input.sourceIdentity(), restored.sourceIdentity());
    var restoredReference = restored.contentRef().asOptional().orElseThrow();
    assertEquals(reference, restoredReference);
    assertEquals("etag-one", restoredReference.checksum());
    assertEquals(reference.connectorOrigin(), restoredReference.connectorOrigin());
  }

  @Test
  void rejectsUnboundOrMismatchedReferencesAndUnsafeLogicalKeys() {
    assertThrows(IllegalArgumentException.class,
        () -> mapper.map(snapshot(KEY, reference(KEY, false))));
    assertThrows(IllegalArgumentException.class,
        () -> mapper.map(snapshot(KEY, reference("incoming/other.csv", true))));
    assertThrows(IllegalArgumentException.class,
        () -> mapper.map(snapshot("incoming/../escaped.csv", reference("incoming/../escaped.csv", true))));
    assertThrows(IllegalArgumentException.class,
        () -> mapper.map(snapshot("/absolute.csv", reference("/absolute.csv", true))));
  }

  private static ObjectSnapshot snapshot(String key, PayloadReference reference) {
    return new ObjectSnapshot("csv-payment-files", "s3", "csv-payments-input", key, null,
        "etag-one", 12L, 0L, "text/csv", Map.of(), reference, null, null);
  }

  private static PayloadReference reference(String key, boolean bound) {
    var origin = new ConnectorPayloadOrigin(
        ConnectorBindingName.of("csv-input-s3"),
        new ConnectorOperationIdentity(
            ConnectorProviderId.of("s3.objects"), "s3", ConnectorOperationKind.OBJECT_SOURCE, 1),
        1, Optional.empty());
    return new PayloadReference("s3", "csv-payments-input", key, "text/csv", "raw",
        "etag-one", 12L, null, Map.of(), bound ? Optional.of(origin) : Optional.empty());
  }
}

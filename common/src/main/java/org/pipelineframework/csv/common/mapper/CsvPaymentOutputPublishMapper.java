package org.pipelineframework.csv.common.mapper;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import com.opencsv.CSVWriter;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import org.pipelineframework.objectpublish.ObjectPayloadChunk;
import org.pipelineframework.objectpublish.ObjectPublishGroupRenderer;
import org.pipelineframework.objectpublish.PagedStreamingObjectPublishMapper;

/**
 * Renders terminal CSV payment outputs as grouped CSV object payloads.
 */
public final class CsvPaymentOutputPublishMapper
    implements PagedStreamingObjectPublishMapper<org.pipelineframework.csv.domain.PaymentOutput> {
    private static final String CSV_HEADER = "'AMOUNT','CSV ID','CURRENCY','FEE','MESSAGE','RECIPIENT','REFERENCE','STATUS'\n";

    @Override
    public ObjectPayloadChunk groupPrefix(String groupKey) {
        return new ObjectPayloadChunk(CSV_HEADER.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ObjectPayloadChunk groupSuffix(String groupKey, Map<String, String> combinedMetadata) {
        return ObjectPayloadChunk.EMPTY;
    }

    @Override
    public Map<String, String> combinePageMetadata(
        String groupKey,
        Map<String, String> accumulated,
        Map<String, String> pageMetadata) {
        long previous = Long.parseLong(accumulated.getOrDefault("recordCount", "0"));
        long current = Long.parseLong(pageMetadata.getOrDefault("recordCount", "0"));
        return Map.of("recordCount", String.valueOf(Math.addExact(previous, current)));
    }

    @Override
    public String groupKey(org.pipelineframework.csv.domain.PaymentOutput item) {
        if (item == null) {
            throw new IllegalArgumentException("Payment output must not be null");
        }
        if (item.csvPaymentsOutputFilename() != null && !item.csvPaymentsOutputFilename().isBlank()) {
            return item.csvPaymentsOutputFilename();
        }
        Path inputFile = item.csvPaymentsInputFilePath();
        if (inputFile == null || inputFile.getFileName() == null) {
            throw new IllegalArgumentException(
                "Payment output must include csvPaymentsOutputFilename or csvPaymentsInputFilePath");
        }
        return inputFile.getFileName().toString();
    }

    @Override
    public ObjectPublishGroupRenderer<org.pipelineframework.csv.domain.PaymentOutput> openGroup(
        String groupKey, org.pipelineframework.csv.domain.PaymentOutput firstItem) {
        return new CsvPaymentOutputGroupRenderer(groupKey, false);
    }

    @Override
    public ObjectPublishGroupRenderer<org.pipelineframework.csv.domain.PaymentOutput> openPageGroup(
        String groupKey, org.pipelineframework.csv.domain.PaymentOutput firstItem) {
        return new CsvPaymentOutputGroupRenderer(groupKey, true);
    }

    private static final class CsvPaymentOutputGroupRenderer
        implements ObjectPublishGroupRenderer<org.pipelineframework.csv.domain.PaymentOutput> {
        private final PaymentOutputPersistenceMapper paymentOutputPersistenceMapper = new PaymentOutputPersistenceMapper();
        private final String groupKey;
        private final StringWriter writer = new StringWriter();
        private final StatefulBeanToCsv<org.pipelineframework.csv.common.domain.PaymentOutput> csv;
        private final boolean pageBody;
        private boolean firstItem = true;
        private long recordCount;

        private CsvPaymentOutputGroupRenderer(String groupKey, boolean pageBody) {
            this.groupKey = groupKey;
            this.pageBody = pageBody;
            this.csv = new StatefulBeanToCsvBuilder<org.pipelineframework.csv.common.domain.PaymentOutput>(writer)
                .withQuotechar('\'')
                .withSeparator(CSVWriter.DEFAULT_SEPARATOR)
                .build();
        }

        @Override
        public String contentType() {
            return "text/csv";
        }

        @Override
        public ObjectPayloadChunk onItem(org.pipelineframework.csv.domain.PaymentOutput item) {
            try {
                org.pipelineframework.csv.common.domain.PaymentOutput output = paymentOutputPersistenceMapper.toExternal(item);
                sanitizeFormulaCells(output);
                csv.write(output);
                recordCount++;
                ObjectPayloadChunk chunk = drain();
                if (!pageBody || !firstItem) {
                    firstItem = false;
                    return chunk;
                }
                firstItem = false;
                String rendered = new String(chunk.bytes(), StandardCharsets.UTF_8);
                if (!rendered.startsWith(CSV_HEADER)) {
                    throw new IllegalStateException("OpenCSV output header differs from the paged group prefix");
                }
                return new ObjectPayloadChunk(rendered.substring(CSV_HEADER.length()).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("Failed rendering CSV payment output object for group: " + groupKey, e);
            }
        }

        @Override
        public Map<String, String> finalMetadata() {
            return Map.of("recordCount", String.valueOf(recordCount));
        }

        private ObjectPayloadChunk drain() {
            String rendered = writer.toString();
            writer.getBuffer().setLength(0);
            return new ObjectPayloadChunk(rendered.getBytes(StandardCharsets.UTF_8));
        }

        private static void sanitizeFormulaCells(org.pipelineframework.csv.common.domain.PaymentOutput output) {
            Objects.requireNonNull(output, "output must not be null");
            if (output.getCsvId() != null) {
                output.setCsvId(sanitizeCell(output.getCsvId()));
            }
            if (output.getRecipient() != null) {
                output.setRecipient(sanitizeCell(output.getRecipient()));
            }
            if (output.getMessage() != null) {
                output.setMessage(sanitizeCell(output.getMessage()));
            }
        }

        private static String sanitizeCell(String value) {
            Objects.requireNonNull(value, "value must not be null");
            int firstContentIndex = firstContentIndex(value);
            if (firstContentIndex < 0 || "=+-@".indexOf(value.charAt(firstContentIndex)) < 0) {
                return value;
            }
            return "'" + value;
        }

        private static int firstContentIndex(String value) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (!Character.isWhitespace(character)
                        && !Character.isSpaceChar(character)
                        && !Character.isISOControl(character)) {
                    return index;
                }
            }
            return -1;
        }
    }
}

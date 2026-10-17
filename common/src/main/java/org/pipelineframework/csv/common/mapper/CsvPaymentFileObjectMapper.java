package org.pipelineframework.csv.common.mapper;

import java.nio.file.Path;
import java.util.Objects;

import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.objectingest.ObjectSnapshot;
import org.pipelineframework.objectingest.ObjectSnapshotMapper;

/**
 * Projects a framework object snapshot into the CSV Payments first business input.
 */
public final class CsvPaymentFileObjectMapper implements ObjectSnapshotMapper<CsvPaymentsInputFile> {

    @Override
    public CsvPaymentsInputFile map(ObjectSnapshot snapshot) {
        if ("s3".equals(snapshot.provider())) {
            return mapS3(snapshot);
        }
        String localPath = snapshot.localPath();
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalArgumentException("CSV filesystem object snapshot must expose localPath");
        }
        Path file = Path.of(localPath);
        String sourceIdentity = snapshot.etag();
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            sourceIdentity = snapshot.versionId();
        }
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("CSV object snapshot must expose etag or versionId for paging");
        }
        return new CsvPaymentsInputFile(file, file.getParent(), sourceIdentity,
                org.pipelineframework.type.CanonicalFieldValue.absent());
    }

    private static CsvPaymentsInputFile mapS3(ObjectSnapshot snapshot) {
        var reference = Objects.requireNonNull(snapshot.contentRef(), "S3 snapshot must carry a binding-owned contentRef");
        if (!"s3".equals(reference.provider())
                || !Objects.equals(snapshot.container(), reference.container())
                || !Objects.equals(snapshot.key(), reference.key())
                || !Objects.equals(snapshot.versionId(), reference.version())
                || snapshot.sizeBytes() != reference.sizeBytes()
                || reference.connectorOrigin().isEmpty()) {
            throw new IllegalArgumentException("S3 snapshot reference does not match source identity");
        }
        String etag = snapshot.etag();
        if (etag == null || etag.isBlank() || !Objects.equals(etag, reference.checksum())) {
            throw new IllegalArgumentException("S3 snapshot requires a matching ETag for paging");
        }
        Path logicalPath = logicalS3Path(snapshot.container(), snapshot.key());
        return new CsvPaymentsInputFile(logicalPath, logicalPath.getParent(), etag,
                org.pipelineframework.type.CanonicalFieldValue.of(reference));
    }

    /** Stable virtual identity across Path JSON round trips, independent of the process directory. */
    public static Path logicalS3Path(String container, String objectKey) {
        String bucket = safeSegment(container, "bucket");
        String key = Objects.requireNonNull(objectKey, "S3 snapshot key must not be null");
        if (key.isBlank() || key.startsWith("/") || key.contains("\\") || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("S3 snapshot key is not a safe logical path");
        }
        for (String segment : key.split("/", -1)) {
            safeSegment(segment, "key segment");
        }
        return Path.of("/s3", bucket, key);
    }

    private static String safeSegment(String value, String label) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)
                || value.contains("/") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("S3 snapshot has invalid " + label);
        }
        return value;
    }
}

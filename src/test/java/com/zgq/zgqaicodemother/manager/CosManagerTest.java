package com.zgq.zgqaicodemother.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.PutObjectRequest;
import com.zgq.zgqaicodemother.config.CosProperties;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * COS 上传管理器的确定性单元测试：模拟 SDK 客户端，验证 key、元数据、访问 URL 和失败处理。
 */
class CosManagerTest {

    @TempDir
    private Path tempDir;

    private final COSClient cosClient = mock(COSClient.class);

    @Test
    void uploadImageBuildsDateBasedKeyAndPublicUrl() throws Exception {
        CosManager manager = new CosManager(properties(), cosClient);
        Path image = Files.write(tempDir.resolve("cover.jpg"), new byte[]{1, 2, 3, 4});

        String imageUrl = manager.uploadImage(image.toString());

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        PutObjectRequest request = requestCaptor.getValue();
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String expectedKeyPattern = "screenshot/" + datePath + "/[0-9a-f]{32}\\.jpg";
        assertAll(
                () -> assertEquals("test-bucket-1250000000", request.getBucketName()),
                () -> assertTrue(request.getKey().matches(expectedKeyPattern), request.getKey()),
                () -> assertEquals("image/jpeg", request.getMetadata().getContentType()),
                () -> assertEquals(4L, request.getMetadata().getContentLength()),
                () -> assertEquals("public, max-age=31536000", request.getMetadata().getCacheControl()),
                () -> assertEquals(CannedAccessControlList.PublicRead, request.getCannedAcl()),
                () -> assertEquals("https://test-bucket-1250000000.cos.ap-guangzhou.myqcloud.com/"
                        + request.getKey(), imageUrl)
        );
    }

    @Test
    void uploadImageSupportsCustomDomainAndPrefix() throws Exception {
        CosProperties properties = properties();
        properties.setDomain("https://cdn.example.com/");
        properties.setKeyPrefix("/covers");
        CosManager manager = new CosManager(properties, cosClient);
        Path image = Files.write(tempDir.resolve("cover.png"), new byte[]{9, 9});

        String imageUrl = manager.uploadImage(image.toString());

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        PutObjectRequest request = requestCaptor.getValue();
        assertTrue(request.getKey().startsWith("covers/"));
        assertTrue(request.getKey().endsWith(".png"));
        assertEquals("image/png", request.getMetadata().getContentType());
        assertEquals("https://cdn.example.com/" + request.getKey(), imageUrl);
    }

    @Test
    void uploadImageDoesNotSetPublicAclWhenDisabled() throws Exception {
        CosProperties properties = properties();
        properties.setPublicRead(false);
        CosManager manager = new CosManager(properties, cosClient);
        Path image = Files.write(tempDir.resolve("cover.jpeg"), new byte[]{1});

        manager.uploadImage(image.toString());

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        assertNull(requestCaptor.getValue().getCannedAcl());
        assertEquals("image/jpeg", requestCaptor.getValue().getMetadata().getContentType());
    }

    @Test
    void rejectsMissingFileAndUnsupportedExtension() throws Exception {
        CosManager manager = new CosManager(properties(), cosClient);
        assertThrows(BusinessException.class,
                () -> manager.uploadImage(tempDir.resolve("missing.jpg").toString()));

        Path textFile = Files.write(tempDir.resolve("cover.txt"), new byte[]{1});
        assertThrows(BusinessException.class, () -> manager.uploadImage(textFile.toString()));
        verifyNoInteractions(cosClient);
    }

    @Test
    void rejectsBlankBucketAndUnsafeKeyPrefix() throws Exception {
        CosProperties blankBucketProperties = properties();
        blankBucketProperties.setBucket(" ");
        CosManager blankBucketManager = new CosManager(blankBucketProperties, cosClient);
        Path image = Files.write(tempDir.resolve("cover.jpg"), new byte[]{1});
        BusinessException blankBucketError = assertThrows(BusinessException.class,
                () -> blankBucketManager.uploadImage(image.toString()));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), blankBucketError.getCode());

        CosProperties unsafePrefixProperties = properties();
        unsafePrefixProperties.setKeyPrefix("../escape/");
        CosManager unsafePrefixManager = new CosManager(unsafePrefixProperties, cosClient);
        assertThrows(BusinessException.class,
                () -> unsafePrefixManager.uploadImage(image.toString()));
        verifyNoInteractions(cosClient);
    }

    @Test
    void wrapsUploadFailureAsBusinessException() throws Exception {
        CosManager manager = new CosManager(properties(), cosClient);
        Path image = Files.write(tempDir.resolve("cover.jpg"), new byte[]{1});
        doThrow(new IllegalStateException("cos unavailable"))
                .when(cosClient).putObject(any(PutObjectRequest.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> manager.uploadImage(image.toString()));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), error.getCode());
        verify(cosClient, never()).shutdown();
    }

    @Test
    void shutdownClosesCosClient() {
        CosManager manager = new CosManager(properties(), cosClient);

        manager.shutdown();

        verify(cosClient).shutdown();
    }

    @Test
    void uploadWorkflowImageUsesServerGeneratedKeyAndContentType() throws Exception {
        CosManager manager = new CosManager(properties(), cosClient);
        Path image = Files.write(tempDir.resolve("diagram.png"), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        String imageUrl = manager.uploadWorkflowImage(image.toFile(), "workflow/diagrams/");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        PutObjectRequest request = requestCaptor.getValue();
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertAll(
                () -> assertTrue(request.getKey().matches(
                        "workflow/diagrams/" + datePath + "/[0-9a-f]{32}\\.png"), request.getKey()),
                () -> assertEquals("image/png", request.getMetadata().getContentType()),
                () -> assertEquals("https://test-bucket-1250000000.cos.ap-guangzhou.myqcloud.com/"
                        + request.getKey(), imageUrl),
                () -> assertTrue(manager.isAvailable())
        );
    }

    @Test
    void uploadWorkflowImageSupportsSvgButRejectsUnsafePrefixAndExtension() throws Exception {
        CosManager manager = new CosManager(properties(), cosClient);
        Path svg = Files.writeString(tempDir.resolve("diagram.svg"), "<svg></svg>");

        manager.uploadWorkflowImage(svg.toFile(), "workflow/diagrams/");
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        assertEquals("image/svg+xml", requestCaptor.getValue().getMetadata().getContentType());

        Path text = Files.writeString(tempDir.resolve("notes.txt"), "hello");
        assertThrows(BusinessException.class,
                () -> manager.uploadWorkflowImage(text.toFile(), "workflow/diagrams/"));
        assertThrows(BusinessException.class,
                () -> manager.uploadWorkflowImage(svg.toFile(), "../escape/"));
    }

    @Test
    void uploadWorkflowImageRejectsMissingFileAndOversizedFile() throws Exception {
        CosManager manager = new CosManager(properties(), cosClient);
        assertThrows(BusinessException.class,
                () -> manager.uploadWorkflowImage(tempDir.resolve("missing.png").toFile(), "workflow/"));

        Path empty = Files.createFile(tempDir.resolve("empty.png"));
        assertThrows(BusinessException.class,
                () -> manager.uploadWorkflowImage(empty.toFile(), "workflow/"));
        verifyNoInteractions(cosClient);
    }

    @Test
    void isAvailableFalseWhenClientCannotBeCreated() {
        CosProperties properties = new CosProperties();
        properties.setSecretId("");
        properties.setSecretKey("");
        properties.setBucket("");

        assertFalse(new CosManager(properties).isAvailable());
    }

    private CosProperties properties() {
        CosProperties properties = new CosProperties();
        properties.setSecretId("test-secret-id");
        properties.setSecretKey("test-secret-key");
        properties.setRegion("ap-guangzhou");
        properties.setBucket("test-bucket-1250000000");
        properties.setKeyPrefix("screenshot/");
        properties.setPublicRead(true);
        return properties;
    }
}

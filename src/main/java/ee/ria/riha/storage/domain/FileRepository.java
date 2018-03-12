package ee.ria.riha.storage.domain;

import ee.ria.riha.storage.domain.model.FileResource;
import ee.ria.riha.storage.util.CompositeFilterRequest;
import ee.ria.riha.storage.util.Pageable;
import ee.ria.riha.storage.util.PagedGridResponse;
import ee.ria.riha.storage.util.StorageRepositoryUriHelper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

/**
 * Stand alone repository for file resource upload and download.
 */
public class FileRepository {

    private static final String FILE_PATH = "/file";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public FileRepository(RestTemplate restTemplate, String baseUrl) {
        Assert.notNull(restTemplate, "restTemplate must be provided");
        Assert.notNull(baseUrl, "baseUrl must be provided");
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public PagedGridResponse<FileResource> list(CompositeFilterRequest filterRequest, Pageable pageable) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl).path(FILE_PATH);

        StorageRepositoryUriHelper.setCompositeFilter(uriBuilder, pageable, filterRequest);

        ResponseEntity<PagedGridResponse<FileResource>> responseEntity = restTemplate.exchange(
                uriBuilder.build(false).toUriString(),
                HttpMethod.GET, null,
                new ParameterizedTypeReference<PagedGridResponse<FileResource>>() {
                });

        return responseEntity.getBody();
    }

    /**
     * Convenience method for file resource upload without association with info system. See {@link #upload(InputStream,
     * UUID, String, String)} for details.
     *
     * @param inputStream file resource input stream
     * @param fileName    file resource name
     * @param contentType MIME content type
     * @return uploaded file resource UUID
     */
    public UUID upload(InputStream inputStream, String fileName, String contentType) {
        return upload(inputStream, null, fileName, contentType);
    }

    /**
     * Uploads file resource to RIHA-Storage and optionally associates it with info system. File resource is associated
     * with info system when info system UUID is provided.
     *
     * @param inputStream    file resource input stream
     * @param infoSystemUuid UUID of associated info system or null
     * @param fileName       file resource name
     * @param contentType    MIME content type
     * @return uploaded file resource UUID
     */
    public UUID upload(InputStream inputStream, UUID infoSystemUuid, String fileName, String contentType) {
        Assert.notNull(inputStream, "uploaded file input stream must be defined");
        Assert.hasText(fileName, "uploaded file name must be defined");
        Assert.hasText(contentType, "uploaded file content type must be defined");

        HttpEntity<InputStreamResource> filePart = createFilePart(inputStream, fileName, contentType);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>(1);
        parts.add("file", filePart);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl).path(FILE_PATH);
        if (infoSystemUuid != null) {
            uriBuilder.queryParam("infoSystemUuid", infoSystemUuid.toString());
        }

        String response = restTemplate.postForObject(uriBuilder.toUriString(),
                new HttpEntity<>(parts, headers), String.class);

        return UUID.fromString(response);
    }

    private HttpEntity<InputStreamResource> createFilePart(InputStream inputStream, String fileName,
                                                           String contentType) {
        InputStreamResource part = new MultipartInputStreamFileResource(inputStream, fileName);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);

        return new HttpEntity<>(part, headers);
    }

    /**
     * Convenience method for file resource download. See {@link #download(UUID, UUID)} for more details.
     *
     * @param fileUuid UUID of file resource
     * @return file resource response entity
     * @throws IOException in case of file resource streaming problems
     */
    public ResponseEntity download(UUID fileUuid) throws IOException {
        return download(fileUuid, null);
    }

    /**
     * Downloads single file resource from RIHA-Storage. While info system UUID is optional and can be left null, when
     * provided will match file resource exactly by file resource UUID and info system UUID.
     *
     * @param fileUuid       UUID of file resource
     * @param infoSystemUuid UUID of info system or null if exact matching not required
     * @return file resource response entity
     * @throws IOException in case of file resource streaming problems
     */
    public ResponseEntity download(UUID fileUuid, UUID infoSystemUuid) throws IOException {
        Assert.notNull(fileUuid, "downloaded file UUID must be provided");

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(FILE_PATH)
                .path("/")
                .path(fileUuid.toString());

        if (infoSystemUuid != null) {
            uriBuilder.queryParam("infoSystemUuid", infoSystemUuid.toString());
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(uriBuilder.toUriString()).openConnection();

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(conn.getResponseCode());
        if (conn.getResponseCode() != HTTP_OK) {
            return builder.build();
        }

        copyHeaderIfPresent(conn, builder, HttpHeaders.CONTENT_LENGTH);
        copyHeaderIfPresent(conn, builder, HttpHeaders.CONTENT_TYPE);
        copyHeaderIfPresent(conn, builder, HttpHeaders.CONTENT_DISPOSITION);

        return builder.body(new InputStreamResource(conn.getInputStream()));
    }

    private void copyHeaderIfPresent(HttpURLConnection urlConnection, ResponseEntity.BodyBuilder responseBuilder,
                                     String headerName) {
        if (urlConnection.getHeaderField(headerName) != null) {
            responseBuilder.header(headerName, urlConnection.getHeaderField(headerName));
        }
    }

    private static class MultipartInputStreamFileResource extends InputStreamResource {
        private final String filename;

        MultipartInputStreamFileResource(InputStream inputStream, String filename) {
            super(inputStream);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return this.filename;
        }

        @Override
        public long contentLength() {
            return -1;
        }

    }

}

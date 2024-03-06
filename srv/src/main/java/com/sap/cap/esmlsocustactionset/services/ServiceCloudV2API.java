package com.sap.cap.esmlsocustactionset.services;

import java.io.IOException;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cap.esmlsocustactionset.destinations.ServiceCloudV2Configuration;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ServiceCloudV2API implements ServiceCloudV2 {

    private CloseableHttpClient client;
    private HttpDestination destination;

    public ServiceCloudV2API() {

    }

    public void initServiceCloudV2Api() {
        this.destination = DestinationAccessor.getDestination(ServiceCloudV2Configuration.BTP_SVC_INT).asHttp();
        this.client = (CloseableHttpClient) HttpClientAccessor.getHttpClient(this.destination);
    }

    public HttpResponse getCaseById(String caseId) throws ClientProtocolException, IOException {
        log.debug("Reading case by ID from Service Cloud V2...");
        String uri = CASE_SERVICE_URI.concat(caseId);
        log.info("Attempt to connect to: {}", uri);
        HttpGet request = new HttpGet(uri);

        CloseableHttpResponse response = this.client.execute(request);
        return response;
    }

    public JsonNode updateCaseById(String caseId, String json) throws ClientProtocolException, IOException {
        log.debug("Updating case on Service Cloud V2...");
        String eTag = null;
        JsonNode result = null;
        // Get ETag header value for update
        CloseableHttpResponse responseGet = (CloseableHttpResponse) this.getCaseById(caseId);

        if (responseGet != null && responseGet.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            Header[] headers = responseGet.getHeaders(HttpHeaders.ETAG);
            if (headers != null && headers.length > 0) {
                eTag = headers[0].getValue();
            }
        } else {
            log.warn("Details of case {} could not be read.", caseId);
        }

        // Continue if ETag value is valid
        if (eTag != null && !eTag.equalsIgnoreCase("")) {
            log.debug("ETag: {}", eTag);
            log.debug("JSON Payload: {}", json);
            responseGet.close();
            HttpPatch request = new HttpPatch(CASE_SERVICE_URI.concat(caseId));
            final StringEntity entity = new StringEntity(json);
            request.setEntity(entity);
            request.setHeader(HttpHeaders.IF_MATCH, eTag);
            request.setHeader(HttpHeaders.ACCEPT, "application/json");
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

            CloseableHttpResponse responsePatch = this.client.execute(request);

            if (responsePatch != null && responsePatch.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                log.error("Call to case service failed with code: ", responsePatch.getStatusLine().getStatusCode());
                throw new RuntimeException(
                        "Call to case service failed with code: " + responsePatch.getStatusLine().getStatusCode());
            }

            try {
                HttpEntity httpEntity = responsePatch.getEntity();

                ObjectMapper mapper = new ObjectMapper();
                result = mapper.readTree(EntityUtils.toString(httpEntity));
            } finally {
                responsePatch.close();
            }

        }
        return result;
    }

    public JsonNode getEmailDetails(String emailId) throws ClientProtocolException, IOException {
        log.debug("Reading email by ID from Service Cloud V2...");
        String uri = EMAIL_SERVICE_URI.concat(emailId);
        return this.executeGet(uri);
    }

    public JsonNode getNoteDetails(String noteId) throws ClientProtocolException, IOException {
        log.debug("Reading note by ID from Service Cloud V2...");
        String uri = NOTES_SERVICE_URI.concat(noteId);
        return this.executeGet(uri);
    }

    public JsonNode executeGet(String uri) throws ClientProtocolException, IOException {
        log.info("Attempt to connect to: {}", uri);
        HttpGet request = new HttpGet(uri);

        HttpResponse response = this.client.execute(request);

        if (response != null && response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            log.error("Call to service failed with code: ", response.getStatusLine().getStatusCode());
            throw new RuntimeException(
                    "Call to service failed with code: " + response.getStatusLine().getStatusCode());
        }

        HttpEntity entity = response.getEntity();

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(EntityUtils.toString(entity));
    }
}

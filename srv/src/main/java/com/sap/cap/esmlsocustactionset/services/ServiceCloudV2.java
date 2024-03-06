package com.sap.cap.esmlsocustactionset.services;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;

import com.fasterxml.jackson.databind.JsonNode;

public interface ServiceCloudV2 {

    public static String CASE_SERVICE_URI = "sap/c4c/api/v1/case-service/cases/";
    public static String EMAIL_SERVICE_URI = "sap/c4c/api/v1/email-service/emails/";
    public static String NOTES_SERVICE_URI = "sap/c4c/api/v1/note-service/notes/";

    public static String ORIGIN_EMAIL = "EMAIL";
    public static String ORIGIN_MANUAL = "MANUAL_DATA_ENTRY";

    public void initServiceCloudV2Api();
    public HttpResponse getCaseById(String caseId) throws ClientProtocolException, IOException;
    public JsonNode updateCaseById(String caseId, String json) throws ClientProtocolException, IOException;
    public JsonNode getEmailDetails(String emailId) throws ClientProtocolException, IOException;
    public JsonNode getNoteDetails(String noteId) throws ClientProtocolException, IOException;
    public JsonNode executeGet(String uri) throws ClientProtocolException, IOException;
}

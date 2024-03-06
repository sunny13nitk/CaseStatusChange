package com.sap.cap.esmlsocustactionset.handlers;

import static cds.gen.logsservice.LogsService_.ESM_CHANGE_LOGS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cap.esmlsocustactionset.services.ServiceCloudV2;
import com.sap.cds.CdsData;
import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.cqn.CqnInsert;
import com.sap.cds.services.request.UserInfo;

import cds.gen.com.sap.cap.esmlsocustactionset.EsmOnChangeLogs;
import cds.gen.logsservice.EsmChangeLogs;
import cds.gen.logsservice.LogsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/case")
public class EsmLsoCaseChangeHandler {

    @Autowired
    private UserInfo userInfo;

    @Autowired
    private LogsService logsService;

    @Autowired
    private ServiceCloudV2 serviceCloudV2;

    @PostMapping("/onchange/status")
    public String onStatusChangeEvent(HttpServletRequest request) {
        String returnValue = null;
        String httpBody = null;
        String caseIdString = null;
        String caseDisplayId = null;
        String caseType = null;
        String origin = null;
        String errorString = null;

        if (userInfo.isSystemUser() && userInfo.isAuthenticated()) {
            log.info("System User Name: " + userInfo.getName());
            log.info("System User is Authenticated: " + userInfo.isAuthenticated());

            if (request != null) {
                try {
                    httpBody = this.getBody(request);
                } catch (IOException e) {
                    errorString = "{\n\t\"Error\" : \" " + e.getMessage() + "\"\n}";
                    log.error(errorString);
                    returnValue = errorString;
                }

                if (httpBody != null && httpBody.length() > 0) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode payload = null;
                    try {
                        payload = mapper.readTree(httpBody);
                    } catch (JsonMappingException e) {
                        errorString = "{\n\t\"Error\" : \" " + e.getMessage() + "\"\n}";
                        log.error(errorString);
                        returnValue = errorString;
                    } catch (JsonProcessingException e) {
                        errorString = "{\n\t\"Error\" : \" " + e.getMessage() + "\"\n}";
                        log.error(errorString);
                        returnValue = errorString;
                    }
                    if (payload != null) {
                        // Read Case ID
                        caseDisplayId = payload.findValue("displayId").asText();
                        caseIdString = payload.findValue("id").asText();
                        Integer caseId = Integer.valueOf(caseDisplayId);
                        log.info("Case display ID: " + caseDisplayId);
                        log.info("Case ID: " + caseIdString);

                        // Read Case Type
                        caseType = payload.findValue("caseType").asText();

                        // Read origin
                        origin = payload.findValue("origin").asText();

                        JsonNode current = payload.findValue("currentImage");
                        JsonNode before = payload.findValue("beforeImage");

                        if (current != null && before == null) {// create event
                            errorString = "Not a change event. Looks rather like a create event.";
                            log.error(errorString);
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorString);
                        }
                        if (current != null && before != null) {// change event
                            // Find out which attributes' values has changed
                            CdsData changedAttr = this.classifyChange(current, before);
                            if (changedAttr != null) {// Either it is a definite attribute which has changed...
                                log.info("Changed attribute: {}", changedAttr);
                                Iterator<String> keyIt = changedAttr.keySet().iterator();
                                while (keyIt.hasNext()) {// Go through all changes
                                    String key = keyIt.next().toString();
                                    CdsData values = (CdsData) changedAttr.get(key);
                                    Object beforeValue = values.get("beforeValue");
                                    Object currentValue = values.get("currentValue");
                                    if (beforeValue != null && currentValue != null) {// Regular change of single
                                                                                      // attribute
                                                                                      // values
                                        String strBeforeValue = beforeValue.toString();
                                        String strCurrentValue = currentValue.toString();
                                        String strJson = null;
                                        switch (key) {
                                            case "status":// Status transition
                                                if (caseType.equalsIgnoreCase("ZLSS")// SAP Learning
                                                        && strBeforeValue.equalsIgnoreCase("Z8")) {// Going from
                                                                                                   // "Customer
                                                                                                   // Action" to *
                                                    log.info("Changing extension field CustomerEmailSent to false.");
                                                    CdsData extField = CdsData.create();
                                                    extField.put("CustomerEmailSent", Boolean.valueOf(false));
                                                    CdsData extensions = CdsData.create();
                                                    extensions.put("extensions", extField);
                                                    strJson = extensions.toJson();
                                                }
                                                break;

                                            default:
                                                break;
                                        }
                                        // apply changes to case
                                        if (strJson != null && !strJson.equalsIgnoreCase("")) {
                                            try {
                                                log.debug("Changing case " + caseDisplayId + " with " + strJson);
                                                serviceCloudV2.updateCaseById(caseIdString, strJson);
                                                log.debug("Case " + caseIdString + " updated successfully.");
                                                this.addChangeLogEntry(caseId, EsmOnChangeLogs.Operation.STATUS_CHANGE,
                                                        EsmOnChangeLogs.Status.SUCCESS);
                                            } catch (Exception e) {
                                                log.error(e.getMessage());
                                            }
                                        }
                                    }
                                }
                            } else { // ...or we have found a change in the case without an attribute change,
                                     // e.g. email sent out or new note added
                                     // Initialize the change action
                                CdsData extensions = null;
                                // 1. Check outbound e-mails being sent out
                                JsonNode newRelatedObjects = current.findValue("relatedObjects");
                                JsonNode oldRelatedObjects = before.findValue("relatedObjects");
                                int sizeOld = 0;
                                int sizeNew = 0;
                                if (oldRelatedObjects != null) {
                                    sizeOld = oldRelatedObjects.size();
                                }
                                if (newRelatedObjects != null) {
                                    sizeNew = newRelatedObjects.size();
                                }
                                if (sizeNew > sizeOld) {// something new was added to the case
                                    CdsData newEmail = this.isNewOutboundEmailSent(oldRelatedObjects,
                                            newRelatedObjects);
                                    if (newEmail != null) {
                                        String emailId = newEmail.get("objectId").toString();
                                        try {
                                            JsonNode emailDetails = serviceCloudV2.getEmailDetails(emailId);
                                            JsonNode direction = emailDetails.path("value").path("direction");
                                            JsonNode dataOrigin = emailDetails.path("value").path("dataOrigin");
                                            if (direction.asText("direction").equalsIgnoreCase("OUTBOUND") &&
                                                    dataOrigin.asText("dataOrigin").equalsIgnoreCase("CHANNEL")) {
                                                log.debug("New Outbound Email: {}",
                                                        emailDetails.path("value").path("subject")
                                                                .asText());
                                                CdsData extField = CdsData.create();
                                                extField.put("CustomerEmailSent", Boolean.valueOf(true));
                                                extensions = CdsData.create();
                                                extensions.put("extensions", extField);
                                            }
                                        } catch (Exception e) {
                                            log.error(e.getMessage());
                                        }
                                    }
                                }
                                // 2. Check for new (external) notes only if manual data entry
                                if (origin.equalsIgnoreCase(ServiceCloudV2.ORIGIN_MANUAL)) {

                                    JsonNode newNotesObjects = current.findValue("notes");
                                    JsonNode oldNotesObjects = before.findValue("notes");
                                    sizeOld = 0;
                                    sizeNew = 0;
                                    if (oldNotesObjects != null) {
                                        sizeOld = oldNotesObjects.size();
                                    }
                                    if (newNotesObjects != null) {
                                        sizeNew = newNotesObjects.size();
                                    }
                                    if (sizeNew > sizeOld) {// something new was added to the case
                                        CdsData newNote = this.isNewNoteAdded(oldNotesObjects,
                                                newNotesObjects);
                                        if (newNote != null) {
                                            String noteId = newNote.get("noteId").toString();
                                            try {
                                                JsonNode noteDetails = serviceCloudV2.getNoteDetails(noteId);
                                                JsonNode hostObjectId = noteDetails.path("value")
                                                        .path("hostObjectType");
                                                JsonNode noteTypeCode = noteDetails.path("value").path("noteTypeCode");
                                                if (hostObjectId.asText("2886").equalsIgnoreCase("2886") &&
                                                        noteTypeCode.asText("ZSF1").equalsIgnoreCase("ZSF1")) {
                                                    log.debug("New external note: {}", noteId);
                                                    CdsData extField = CdsData.create();
                                                    extField.put("CustomerEmailSent", Boolean.valueOf(true));
                                                    extensions = CdsData.create();
                                                    extensions.put("extensions", extField);
                                                }
                                            } catch (Exception e) {
                                                log.error(e.getMessage());
                                            }
                                        }
                                    }
                                }
                                // Apply changes to the case if applicable
                                if (extensions != null) {
                                    try {
                                        log.debug("Changing case " + caseDisplayId + " with " + extensions.toJson());
                                        serviceCloudV2.updateCaseById(caseIdString, extensions.toJson());
                                        log.debug("Case " + caseIdString + " updated successfully.");
                                        this.addChangeLogEntry(caseId, EsmOnChangeLogs.Operation.STATUS_CHANGE,
                                                EsmOnChangeLogs.Status.SUCCESS);
                                    } catch (Exception e) {
                                        log.error(e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                } else {
                    errorString = "Payload is either empty or cannot be validated correctly.";
                    log.error(errorString);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorString);
                }
            }

        } else {
            errorString = "Either not a system-user, or not properly authenticated.";
            log.error(errorString);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorString);
        }

        return returnValue;
    }

    /**
     * Identification of the changed attribute, e.g. subject
     * 
     * @param currentImage The case data after the change
     * @param beforeImage  The case data prior to the change
     * @return CdsData with the name of the changed attribute and both values
     */
    protected CdsData classifyChange(JsonNode currentImage,
            JsonNode beforeImage) {
        CdsData changedAttribute = null;
        if (currentImage != null && beforeImage != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = currentImage.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if (value.isTextual()) {
                    String currentValue = value.asText();
                    String beforeValue = beforeImage.findValue(key).asText();
                    if (!currentValue.equalsIgnoreCase(beforeValue)) {
                        if (changedAttribute == null) {
                            changedAttribute = CdsData.create(new HashMap<String, Object>());
                        }
                        HashMap<String, String> values = new HashMap<>();
                        values.put("beforeValue", beforeValue);
                        values.put("currentValue", currentValue);
                        changedAttribute.put(key, values);
                    }
                } else if (value.isBoolean()) {
                    Boolean currentValue = Boolean.valueOf(value.asBoolean());
                    Boolean beforeValue = Boolean.valueOf(beforeImage.findValue(key).asBoolean());
                    if (!currentValue.equals(beforeValue)) {
                        if (changedAttribute == null) {
                            changedAttribute = CdsData.create(new HashMap<String, Object>());
                        }
                        HashMap<String, Boolean> values = new HashMap<>();
                        values.put("beforeValue", beforeValue);
                        values.put("currentValue", currentValue);
                        changedAttribute.put(key, values);
                        // break;
                    }
                } else if (value.isObject()) { // Nested structures, e.g. Account, Extensions,
                    // Catalog --> run recursively
                    switch (key) {
                        case "extensions":
                            CdsData nestedChangedAttribute = classifyChange(value, beforeImage.get(key));
                            if (nestedChangedAttribute != null) {
                                if (changedAttribute == null) {
                                    changedAttribute = CdsData.create(new HashMap<String, Object>());
                                }
                                changedAttribute.put(key, nestedChangedAttribute);
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        return changedAttribute;
    }

    /**
     * Determination of whether there was a new email sent out. Emails are captured
     * in the case's "Related Objects" relationship. They are of type "39", and if
     * the size of the collections of the current image and the before image differ
     * it is safe to assume that a new email has been found
     * 
     * @param oldRelatedObjects Collection of beforeImage related objects
     * @param newRelatedObjects Collection of currentImage related objects
     * @return CdsData with the information of the email object
     */
    public CdsData isNewOutboundEmailSent(JsonNode oldRelatedObjects, JsonNode newRelatedObjects) {
        CdsData result = null;
        // The idea is to iterate over the collection of related
        // objects from the currentImage section and find the one
        // entry which was not available in the beforeImage
        Iterator<Map.Entry<String, JsonNode>> fieldsNew = newRelatedObjects.fields();
        while (fieldsNew.hasNext()) {
            Map.Entry<String, JsonNode> newEntry = fieldsNew.next();
            JsonNode valueNew = newEntry.getValue();
            String type = valueNew.findValue("type").asText();
            if (!type.equalsIgnoreCase("39")) {// Type 39 = Email
                continue;
            }
            // Start comparing the new ID with the old ID values
            String newId = valueNew.findValue("id").asText();

            boolean oldValueExists = false;
            if (oldRelatedObjects != null) {// e.g. first email ever sent
                Iterator<Map.Entry<String, JsonNode>> fieldsOld = oldRelatedObjects.fields();
                while (fieldsOld.hasNext()) {
                    Map.Entry<String, JsonNode> oldEntry = fieldsOld.next();
                    JsonNode valueOld = oldEntry.getValue();
                    String oldId = valueOld.findValue("id").asText();
                    if (oldId.equals(newId)) {// Check for identity
                        oldValueExists = true;
                        break;
                    }
                }
            }
            // Continue in case nothing new discovered
            if (oldValueExists) {
                continue;
            } else {
                // New entry has been found
                result = CdsData.create();
                result.put("objectId", newId);
                break;
            }
        }
        return result;
    }

    /**
     * Determination of whether there was a new note added to the case.
     * 
     * @param oldNotesObjects Collection of beforeImage note objects
     * @param newNotesObjects Collection of currentImage note objects
     * @return CdsData with the information of the added note object
     */
    public CdsData isNewNoteAdded(JsonNode oldNotesObjects, JsonNode newNotesObjects) {
        CdsData result = null;
        // The idea is to iterate over the collection of related
        // objects from the currentImage section and find the one
        // entry which was not available in the beforeImage
        Iterator<Map.Entry<String, JsonNode>> fieldsNew = newNotesObjects.fields();
        while (fieldsNew.hasNext()) {
            Map.Entry<String, JsonNode> newEntry = fieldsNew.next();
            JsonNode valueNew = newEntry.getValue();

            // Start comparing the new ID with the old ID values
            String newId = valueNew.findValue("noteId").asText();
            boolean oldValueExists = false;
            if (oldNotesObjects != null) {// e.g. first note ever added
                Iterator<Map.Entry<String, JsonNode>> fieldsOld = oldNotesObjects.fields();
                while (fieldsOld.hasNext()) {
                    Map.Entry<String, JsonNode> oldEntry = fieldsOld.next();
                    JsonNode valueOld = oldEntry.getValue();
                    String oldId = valueOld.findValue("noteId").asText();
                    if (oldId.equals(newId)) {// Check for identity
                        oldValueExists = true;
                        break;
                    }
                }
            }
            // Continue in case nothing new discovered
            if (oldValueExists) {
                continue;
            } else {
                // New entry has been found
                result = CdsData.create();
                result.put("noteId", newId);
                break;
            }
        }
        return result;
    }

    /**
     * Extracts the HTTP body from the request
     * 
     * @param request
     * @return - the body/payload
     * @throws IOException
     */
    public String getBody(HttpServletRequest request) throws IOException {

        String body = null;
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = null;

        try {
            InputStream inputStream = request.getInputStream();
            if (inputStream != null) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                char[] charBuffer = new char[128];
                int bytesRead = -1;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    stringBuilder.append(charBuffer, 0, bytesRead);
                }
            } else {
                stringBuilder.append("");
            }
        } catch (IOException ex) {
            throw ex;
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ex) {
                    throw ex;
                }
            }
        }

        body = stringBuilder.toString();
        return body;
    }

    /**
     * 
     * @param caseId
     */
    private void addChangeLogEntry(Integer caseId, String operation, Integer status) {
        log.debug("Adding change log to DB: " + caseId + ", " + operation + ", " + status);
        Result result = null;
        EsmChangeLogs logs = EsmChangeLogs.create();
        logs.setCaseId(caseId);
        logs.setStatus(status);
        logs.setOperation(operation);
        CqnInsert insert = Insert.into(ESM_CHANGE_LOGS).entry(logs);
        result = this.logsService.run(insert);
        if (result != null && result.first().isPresent()) {
            log.debug("New entry added successfully.");
            Row row = result.single();
            log.debug("Created logs entry at {}", row.get("createdAt"));
        } else {
            log.warn("Something went wrong when adding new entry.");
        }
    }
}
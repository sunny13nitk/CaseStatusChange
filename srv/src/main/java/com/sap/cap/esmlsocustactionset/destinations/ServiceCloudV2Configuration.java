package com.sap.cap.esmlsocustactionset.destinations;

import com.sap.cap.esmlsocustactionset.services.ServiceCloudV2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ServiceCloudV2Configuration implements ApplicationListener<ContextRefreshedEvent> {

    public static String BTP_SVC_INT = "BTP_SVC_INT";

    @Autowired
    private ServiceCloudV2 serviceCloudV2;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("Initializing destination {} from BTP.", BTP_SVC_INT);
        Destination destination = null;
        try {
            destination = DestinationAccessor.getDestination(BTP_SVC_INT);
        } catch (Exception e) {
            log.warn("Unable to read destination from destination service on BTP.");
        }
        if (destination != null) {
            log.info("Found destination: {}", destination.toString());
            this.serviceCloudV2.initServiceCloudV2Api();
        }
    }
}

package com.compliance.gateway.models;

import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Data;

@Data
@Document(collection = "api_templates")
public class ApiTemplate {
    private String id;
    private String apiName;
    private String integrationType;
    private String rawPostmanTemplate;
    private String sampleRequest;
    private String sampleResponse;
}
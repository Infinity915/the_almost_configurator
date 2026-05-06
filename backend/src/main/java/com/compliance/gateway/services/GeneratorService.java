package com.compliance.gateway.services;

import com.compliance.gateway.dto.GenerateCollectionRequest;
import com.compliance.gateway.models.ApiTemplate;
import com.compliance.gateway.repositories.ApiTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Service
public class GeneratorService {

    @Autowired
    private ApiTemplateRepository templateRepository;

    public String generatePostmanCollection(GenerateCollectionRequest request) {

        Iterable<ApiTemplate> selectedTemplates = templateRepository.findAllById(request.getSelectedApiIds());
        List<String> injectedItems = new ArrayList<>();

        for (ApiTemplate template : selectedTemplates) {
            String rawJson = template.getRawPostmanTemplate();

            // Inject the credentials into the saved template
            String injectedJson = rawJson
                    .replace("{{cred_client_id}}", request.getClientId() != null ? request.getClientId() : "")
                    .replace("{{cred_client_secret}}", request.getClientSecret() != null ? request.getClientSecret() : "")
                    .replace("{{tenant-name}}", request.getTenantName() != null ? request.getTenantName() : "")
                    .replace("{{requestorEmail}}", request.getRequestorEmail() != null ? request.getRequestorEmail() : "");

            injectedItems.add(injectedJson);
        }

        String baseUrl = (request.getInstanceUrl() != null && !request.getInstanceUrl().trim().isEmpty())
                ? request.getInstanceUrl()
                : "https://leah.com";

        String variablesJson = String.format("""
                    "variable": [
                        { "key": "client_id", "value": "%s" },
                        { "key": "client_secret", "value": "%s" },
                        { "key": "tenant_name", "value": "%s" },
                        { "key": "base_url", "value": "%s" }
                    ]
                """,
                request.getClientId() != null ? request.getClientId() : "",
                request.getClientSecret() != null ? request.getClientSecret() : "",
                request.getTenantName() != null ? request.getTenantName() : "",
                baseUrl);

        String preRequestScript = "\"event\": [\n" +
                "    {\n" +
                "      \"listen\": \"prerequest\",\n" +
                "      \"script\": {\n" +
                "        \"type\": \"text/javascript\",\n" +
                "        \"exec\": [\n" +
                "          \"pm.sendRequest({\",\n" +
                "          \"    url: pm.environment.get('base_url') + '/oauth/token',\",\n" +
                "          \"    method: 'POST',\",\n" +
                "          \"    header: 'Content-Type: application/x-www-form-urlencoded',\",\n" +
                "          \"    body: {\",\n" +
                "          \"        mode: 'urlencoded',\",\n" +
                "          \"        urlencoded: [\",\n" +
                "          \"            {key: 'client_id', value: pm.environment.get('client_id')},\",\n" +
                "          \"            {key: 'client_secret', value: pm.environment.get('client_secret')},\",\n" +
                "          \"            {key: 'grant_type', value: 'client_credentials'}\",\n" +
                "          \"        ]\",\n" +
                "          \"    }\",\n" +
                "          \"}, function (err, res) {\",\n" +
                "          \"    if (!err && res.code === 200) {\",\n" +
                "          \"        pm.environment.set('access_token', res.json().access_token);\",\n" +
                "          \"    }\",\n" +
                "          \"});\"\n" +
                "        ]\n" +
                "      }\n" +
                "    }\n" +
                "  ]";

        String finalItemsString = String.join(",\n", injectedItems);
        String collectionName = "Leah_" + request.getTenantName() + "_Collection";

        return "{\n" +
                "  \"info\": {\n" +
                "    \"name\": \"" + collectionName + "\",\n" +
                "    \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"\n" +
                "  },\n" +
                "  " + variablesJson + ",\n" +
                "  " + preRequestScript + ",\n" +
                "  \"item\": [\n" + finalItemsString + "\n  ]\n" +
                "}";
    }
}
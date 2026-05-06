package com.compliance.gateway.services;

import com.compliance.gateway.models.ApiTemplate;
import com.compliance.gateway.repositories.ApiTemplateRepository;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;

@Service
public class SwaggerImporterService {
    private static final Logger logger = LoggerFactory.getLogger(SwaggerImporterService.class);

    @Autowired
    private ApiTemplateRepository repository;

    public void importFromUrl(String swaggerUrl) throws Exception {
        try {
            logger.info("Starting import from URL: {}", swaggerUrl);

            // 1. MAGIC: Configure parser to automatically follow all $ref sticky notes!
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            options.setResolveFully(true);

            OpenAPI openAPI = new OpenAPIV3Parser().read(swaggerUrl, null, options);

            if (openAPI == null || openAPI.getPaths() == null) {
                logger.error("Failed to parse OpenAPI from URL: {}", swaggerUrl);
                throw new RuntimeException("Failed to parse OpenAPI - null response or no paths found");
            }

            logger.info("Successfully parsed OpenAPI with {} paths", openAPI.getPaths().size());
            ObjectMapper mapper = new ObjectMapper();
            int templateCount = 0;

            for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
                String path = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();

                Map<PathItem.HttpMethod, Operation> operationMap = pathItem.readOperationsMap();
                for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : operationMap.entrySet()) {
                    String method = opEntry.getKey().name();
                    Operation operation = opEntry.getValue();

                    ApiTemplate template = new ApiTemplate();

                    // Normalize path for Postman
                    String postmanPath = path
                            .replace("{tenantName}", "{{tenant_name}}")
                            .replace("{tenantname}", "{{tenant_name}}")
                            .replace("{TenantName}", "{{tenant_name}}");

                    String id = (method + "_" + path).replaceAll("[/{}]", "_").toLowerCase();

                    template.setId(id);
                    template.setApiName(operation.getSummary() != null ? operation.getSummary() : path);

                    String integrationType = "GENERAL";
                    if (operation.getTags() != null && !operation.getTags().isEmpty()) {
                        integrationType = operation.getTags().get(0);
                    }
                    template.setIntegrationType(integrationType);

                    String sampleRequest = "{}";

                    // 2. EXTRACT BODY: Navigate the native OpenAPI models
                    if (operation.getRequestBody() != null &&
                            operation.getRequestBody().getContent() != null &&
                            operation.getRequestBody().getContent().get("application/json") != null) {

                        Schema<?> schema = operation.getRequestBody().getContent().get("application/json").getSchema();
                        if (schema != null) {
                            try {
                                // Run our recursive builder to generate a fake JSON object
                                Object mockObj = generateMock(schema, new HashSet<>());
                                // Convert the fake object into a pretty JSON string
                                sampleRequest = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mockObj);
                            } catch (Exception e) {
                                logger.warn("Failed to generate mock for {}: {}", id, e.getMessage());
                                sampleRequest = "{}";
                            }
                        }
                    }

                    // 3. Inject Postman Dynamic Variables
                    sampleRequest = sampleRequest.replaceAll(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                            Matcher.quoteReplacement("{{$guid}}"));
                    sampleRequest = sampleRequest.replaceAll(
                            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:\\d{2}|Z)?",
                            Matcher.quoteReplacement("{{$isoTimestamp}}"));

                    template.setSampleRequest(sampleRequest);
                    template.setSampleResponse("{}");

                    // 4. Build strict Postman JSON block
                    String[] pathSegments = postmanPath.split("/");
                    StringBuilder pathArray = new StringBuilder();
                    for (String segment : pathSegments) {
                        if (!segment.isEmpty()) {
                            pathArray.append("\"").append(segment.replace("\"", "\\\"")).append("\",");
                        }
                    }
                    if (pathArray.length() > 0)
                        pathArray.setLength(pathArray.length() - 1);

                    String bodyJson = "";
                    if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD")) {
                        String escapedSampleRequest = sampleRequest
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r");

                        bodyJson = String.format(
                                "\"body\":{\"mode\":\"raw\",\"raw\":\"%s\",\"options\":{\"raw\":{\"language\":\"json\"}}},",
                                escapedSampleRequest);
                    }

                    String rawTemplate = String.format(
                            "{\"name\":\"%s\",\"request\":{\"method\":\"%s\",\"header\":[{\"key\":\"Authorization\",\"value\":\"Bearer {{access_token}}\"},{\"key\":\"Content-Type\",\"value\":\"application/json\"}],%s\"url\":{\"raw\":\"{{base_url}}%s\",\"host\":[\"{{base_url}}\"],\"path\":[%s]}}}",
                            template.getApiName(),
                            method.toUpperCase(),
                            bodyJson,
                            postmanPath,
                            pathArray.toString());

                    template.setRawPostmanTemplate(rawTemplate);
                    repository.save(template);
                    templateCount++;
                }
            }

            logger.info("Successfully imported {} API templates", templateCount);
        } catch (Exception e) {
            logger.error("Error during Swagger import: ", e);
            throw e;
        }
    }

    // --- THE MOCK GENERATOR: Recursively reads OpenAPI types and builds a fake
    // JSON Object ---
    private Object generateMock(Schema<?> schema, Set<String> visited) {
        if (schema == null)
            return null;

        // Prevent infinite loops if a schema references itself
        if (schema.get$ref() != null) {
            if (visited.contains(schema.get$ref()))
                return "{}";
            visited.add(schema.get$ref());
        }

        if (schema.getExample() != null)
            return schema.getExample();

        String type = schema.getType();
        if (type == null) {
            if (schema.getProperties() != null)
                type = "object";
            else
                return "string"; // Fallback
        }

        switch (type) {
            case "object":
                Map<String, Object> map = new HashMap<>();
                if (schema.getProperties() != null) {
                    for (Map.Entry<String, Schema> entry : schema.getProperties().entrySet()) {
                        map.put(entry.getKey(), generateMock(entry.getValue(), visited));
                    }
                }
                return map;
            case "array":
                List<Object> list = new ArrayList<>();
                if (schema.getItems() != null) {
                    list.add(generateMock(schema.getItems(), visited));
                }
                return list;
            case "string":
                if ("date-time".equals(schema.getFormat()))
                    return "2024-01-01T12:00:00Z";
                if ("uuid".equals(schema.getFormat()))
                    return "00000000-0000-0000-0000-000000000000";
                return "string";
            case "integer":
            case "number":
                return 0;
            case "boolean":
                return true;
            default:
                return "";
        }
    }
}
package com.compliance.gateway.controllers;

import com.compliance.gateway.dto.GenerateCollectionRequest;
import com.compliance.gateway.services.GeneratorService;
import com.compliance.gateway.services.SwaggerImporterService;
import com.compliance.gateway.repositories.ApiTemplateRepository;
import com.compliance.gateway.models.ApiTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RestController
@RequestMapping("/api/v1/generator")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class GeneratorController {
    private static final Logger logger = LoggerFactory.getLogger(GeneratorController.class);

    @Autowired
    private GeneratorService generatorService;

    @Autowired
    private ApiTemplateRepository templateRepository;

    @Autowired
    private SwaggerImporterService importerService;

    @GetMapping("/templates")
    public ResponseEntity<List<ApiTemplate>> getAllTemplates() {
        try {
            logger.info("Fetching all API templates");
            List<ApiTemplate> templates = templateRepository.findAll();
            logger.info("Found {} templates", templates.size());
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            logger.error("Error fetching templates: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<String> syncSwagger(@RequestParam String url) {
        try {
            logger.info("Starting sync from URL: {}", url);
            importerService.importFromUrl(url);
            logger.info("Sync completed successfully");
            return ResponseEntity.ok("Sync Complete");
        } catch (Exception e) {
            logger.error("Error during sync: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/download")
    public ResponseEntity<String> downloadCollection(@RequestBody GenerateCollectionRequest request) {
        try {
            logger.info("Generating Postman collection for tenant: {}", request.getTenantName());
            String postmanFileContent = generatorService.generatePostmanCollection(request);
            String filename = "Leah_" + request.getTenantName() + "_Collection.json";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(postmanFileContent);
        } catch (Exception e) {
            logger.error("Error generating collection: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }
}
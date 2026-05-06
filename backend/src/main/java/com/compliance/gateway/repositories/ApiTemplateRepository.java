package com.compliance.gateway.repositories;

import com.compliance.gateway.models.ApiTemplate;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiTemplateRepository extends MongoRepository<ApiTemplate, String> {
    List<ApiTemplate> findByIntegrationType(String integrationType);
}
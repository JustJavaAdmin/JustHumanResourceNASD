package com.justjava.humanresource.kpi.service;

import com.justjava.humanresource.kpi.entity.KpiDefinition;
import com.justjava.humanresource.kpi.repositories.KpiDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KpiDefinitionService {

    private final KpiDefinitionRepository repository;

    public KpiDefinition create(KpiDefinition kpi) {
        kpi.setActive(true);
        return repository.save(kpi);
    }

    public KpiDefinition update(Long id, KpiDefinition kpi) {
        KpiDefinition existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("KPI not found: " + id));

        existing.setCode(kpi.getCode());
        existing.setName(kpi.getName());
        existing.setDescription(kpi.getDescription());
        existing.setCategory(kpi.getCategory());
        existing.setTargetValue(kpi.getTargetValue());
        existing.setUnit(kpi.getUnit());
        existing.setImpactSalary(kpi.isImpactSalary());


        return repository.save(existing);
    }
    public List<KpiDefinition> getAll() {
        return repository.findAll();
    }
}


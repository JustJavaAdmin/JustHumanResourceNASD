package com.justjava.humanresource.integration.fam.controller;

import com.justjava.humanresource.aau.AuthenticationManager;
import com.justjava.humanresource.integration.fam.AssetCategory;
import com.justjava.humanresource.integration.fam.dto.CreateAssetCatalogItemCommand;
import com.justjava.humanresource.integration.fam.dto.UpdateAssetCatalogItemCommand;
import com.justjava.humanresource.integration.fam.entity.AssetCatalogItem;
import com.justjava.humanresource.integration.fam.service.AssetCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetCatalogController {

    private final AssetCatalogService assetCatalogService;
    private final AuthenticationManager authenticationManager;

    @GetMapping("/catalog")
    public List<AssetCatalogItem> listCatalog() {
        assertAssetManager();
        return assetCatalogService.listAll();
    }

    @PostMapping("/catalog")
    public ResponseEntity<AssetCatalogItem> create(@Valid @RequestBody CreateAssetCatalogItemCommand command) {
        assertAssetManager();
        return ResponseEntity.status(HttpStatus.CREATED).body(assetCatalogService.create(command));
    }

    @PutMapping("/catalog/{id}")
    public AssetCatalogItem update(@PathVariable Long id, @Valid @RequestBody UpdateAssetCatalogItemCommand command) {
        assertAssetManager();
        return assetCatalogService.update(id, command);
    }

    @PostMapping("/catalog/{id}/retire")
    public AssetCatalogItem retire(@PathVariable Long id) {
        assertAssetManager();
        return assetCatalogService.retire(id);
    }

    @GetMapping("/categories")
    public List<Option> categories() {
        return Arrays.stream(AssetCategory.values())
                .map(v -> new Option(v.name(), v.getLabel()))
                .toList();
    }

    @GetMapping("/access")
    public Map<String, Object> access() {
        return Map.of("isAssetManager", authenticationManager.isAssetManager());
    }

    private void assertAssetManager() {
        if (!authenticationManager.isAssetManager()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only asset managers can perform this action.");
        }
    }

    public record Option(String value, String label) {
    }
}
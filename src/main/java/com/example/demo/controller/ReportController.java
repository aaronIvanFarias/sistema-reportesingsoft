package com.example.demo.controller;

import com.example.demo.dto.ReportRequest;
import com.example.demo.dto.ReportResponse;
import com.example.demo.model.Report;
import com.example.demo.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping({"/reports", "/api/reports"})
@CrossOrigin(origins = "*")
public class ReportController {

    @Autowired
    private ReportService reportService;

    @PostMapping
    public ResponseEntity<ReportResponse> createReport(
            @RequestBody ReportRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Report report = reportService.requestReportGeneration(request.getStartDate(), request.getEndDate(), idempotencyKey);
        ReportResponse response = reportService.toResponse(report);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/reports/" + report.getId())
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReportResponse> getReportStatus(@PathVariable("id") Long id) {
        Report report = reportService.getReportById(id);
        return ResponseEntity.ok(reportService.toResponse(report));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadReport(@PathVariable("id") Long id) {
        Report report = reportService.getReportById(id);

        if (!"completed".equalsIgnoreCase(report.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build(); // 409 Conflict si no está listo
        }

        File file = new File(report.getFileUrl());
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(resource);
    }
}
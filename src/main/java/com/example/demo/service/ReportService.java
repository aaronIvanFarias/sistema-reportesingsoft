package com.example.demo.service;

import com.example.demo.dto.ReportResponse;
import com.example.demo.model.Report;
import com.example.demo.repository.ReportRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

@Service
public class ReportService {

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public Report requestReportGeneration(LocalDate startDate, LocalDate endDate, String idempotencyKey) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rango de fechas inválido: startDate debe ser menor o igual a endDate.");
        }

        // Manejo de Idempotencia
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Report> existingReport = reportRepository.findByIdempotencyKey(idempotencyKey);
            if (existingReport.isPresent()) {
                Report rep = existingReport.get();
                if (startDate.equals(rep.getStartDate()) && endDate.equals(rep.getEndDate())) {
                    return rep; // Retorna el reporte existente si los parámetros coinciden
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "409 Conflict: Idempotency-Key ya usada con parámetros distintos.");
                }
            }
        }

        Report report = new Report();
        report.setStartDate(startDate);
        report.setEndDate(endDate);
        report.setIdempotencyKey(idempotencyKey);
        report.setStatus("queued");
        report = reportRepository.save(report);

        rabbitTemplate.convertAndSend("report_queue", report.getId().toString());

        return report;
    }

    public Report getReportById(Long id) {
        return reportRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reporte no encontrado con ID: " + id));
    }

    public ReportResponse toResponse(Report report) {
        ReportResponse response = new ReportResponse();
        response.setId(report.getId());
        response.setStatus(report.getStatus());
        response.setStartDate(report.getStartDate());
        response.setEndDate(report.getEndDate());
        response.setCreatedAt(report.getCreatedAt());
        response.setUpdatedAt(report.getUpdatedAt());
        response.setRetryCount(report.getRetryCount());
        response.setExportedRows(report.getExportedRows());
        response.setErrorMessage(report.getErrorMessage());

        if ("completed".equalsIgnoreCase(report.getStatus())) {
            response.setDownloadUrl("/reports/" + report.getId() + "/download");
        }

        return response;
    }
}
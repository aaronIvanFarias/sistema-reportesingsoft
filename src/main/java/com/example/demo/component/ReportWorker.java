package com.example.demo.component;

import com.example.demo.model.Report;
import com.example.demo.model.Sale;
import com.example.demo.repository.ReportRepository;
import com.example.demo.repository.SaleRepository;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class ReportWorker {

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private ReportRepository reportRepository;

    @RabbitListener(queuesToDeclare = @Queue("report_queue"))
    public void processReport(String reportIdStr, Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Long reportId = Long.parseLong(reportIdStr);

        Report report = reportRepository.findById(reportId).orElse(null);
        if (report == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        if ("completed".equalsIgnoreCase(report.getStatus())) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        int currentAttempt = report.getRetryCount() + 1;
        report.setRetryCount(currentAttempt);
        report.setStatus("processing");
        reportRepository.save(report);

        System.out.printf("[%s] [WORKER] Procesando Reporte ID: %d | Intento: %d/3\n",
                LocalDateTime.now(), reportId, currentAttempt);

        try {
            // Retardo configurable para simular carga pesada
            Thread.sleep(5000);

            // Escenario de prueba de fallos: si la fecha de inicio es del año 2000, simula un error
            if (report.getStartDate() != null && report.getStartDate().getYear() == 2000 && currentAttempt < 3) {
                throw new RuntimeException("Fallo provocado para simulación de reintentos.");
            }

            // Consultar datos filtrados por rango de fechas
            List<Sale> sales = saleRepository.findBySaleDateBetweenOrderBySaleDateAscIdAsc(
                    report.getStartDate(), report.getEndDate()
            );

            String fileName = "reporte_ventas_" + reportId + ".csv";
            File file = new File(fileName);

            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                writer.println("Sale ID,Customer Name,Product Name,Unit Price,Quantity,Total Amount,Sale Date");

                for (Sale s : sales) {
                    writer.printf("%d,\"%s\",\"%s\",%.2f,%d,%.2f,%s\n",
                            s.getId(),
                            escapeCsv(s.getCustomerName()),
                            escapeCsv(s.getProductName()),
                            s.getPrice(),
                            s.getQuantity(),
                            s.getTotalAmount(),
                            s.getSaleDate());
                }
            }

            report.setExportedRows(sales.size());
            report.setFileUrl(fileName);
            report.setStatus("completed");
            report.setErrorMessage(null);
            reportRepository.save(report);

            System.out.printf("[%s] [WORKER] Reporte ID: %d COMPLETADO con %d filas.\n",
                    LocalDateTime.now(), reportId, sales.size());

            // Manual ACK: confirmación explícita tras guardar correctamente
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            System.err.printf("[%s] [WORKER ERROR] Reporte ID: %d (Intento %d): %s\n",
                    LocalDateTime.now(), reportId, currentAttempt, e.getMessage());

            if (currentAttempt < 3) {
                report.setStatus("retrying");
                report.setErrorMessage("Error transitorio: " + e.getMessage());
                reportRepository.save(report);

                // NACK requeue=true para volver a intentar
                channel.basicNack(deliveryTag, false, true);
            } else {
                report.setStatus("failed");
                report.setErrorMessage("Fallo permanente tras " + currentAttempt + " intentos: " + e.getMessage());
                reportRepository.save(report);

                // Sacar el mensaje de la cola tras agotar reintentos
                channel.basicAck(deliveryTag, false);
            }
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }
}
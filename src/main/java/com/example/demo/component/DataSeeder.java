package com.example.demo.component;

import com.example.demo.model.Sale;
import com.example.demo.repository.SaleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    @Autowired
    private SaleRepository saleRepository;

    @Override
    public void run(String... args) throws Exception {
        if (saleRepository.count() == 0) {
            System.out.println("🌱 Generando 10,000 registros sintéticos de prueba...");
            List<Sale> sales = new ArrayList<>();
            String[] customers = {"Cliente Ana", "Cliente Carlos", "Cliente Beatriz", "Cliente David", "Cliente Elena"};
            
            for (int i = 1; i <= 10000; i++) {
                String customer = customers[i % customers.length];
                String product = "Producto " + i;
                double price = Math.round((10 + (100 - 10) * Math.random()) * 100.0) / 100.0;
                int quantity = (int) (Math.random() * 10) + 1;
                double totalAmount = Math.round((price * quantity) * 100.0) / 100.0;
                LocalDate date = LocalDate.of(2025, 1, 1).plusDays((long) (Math.random() * 365 * 2));

                sales.add(new Sale(customer, product, price, quantity, totalAmount, date));
            }
            
            saleRepository.saveAll(sales);
            System.out.println("✅ Se sembraron 10,000 ventas en PostgreSQL.");
        } else {
            System.out.println("La base de datos ya tiene información, omitiendo generación de prueba.");
        }
    }
}
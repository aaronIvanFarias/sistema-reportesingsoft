package com.example.demo.repository;

import com.example.demo.model.Sale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SaleRepository extends JpaRepository<Sale, Long> {
    List<Sale> findBySaleDateBetweenOrderBySaleDateAscIdAsc(LocalDate startDate, LocalDate endDate);
}
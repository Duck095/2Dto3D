package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.Printer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PrinterRepository extends JpaRepository<Printer, Integer> {
}

package com.messagingagent.repository;

import com.messagingagent.model.SmscSupplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SmscSupplierRepository extends JpaRepository<SmscSupplier, Long> {
    List<SmscSupplier> findByActiveTrue();
}

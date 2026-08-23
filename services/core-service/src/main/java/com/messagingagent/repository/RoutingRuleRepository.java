package com.messagingagent.repository;

import com.messagingagent.model.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {
    List<RoutingRule> findByIsActiveTrueOrderByPriorityAsc();
}

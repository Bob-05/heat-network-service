package ru.hackathon.heatnetworkservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.hackathon.heatnetworkservice.model.Variant;

import java.util.List;

@Repository
public interface VariantRepository extends JpaRepository<Variant, String> {

    List<Variant> findAllByOrderByRankAsc();

    List<Variant> findAllByOrderByScoreAsc();
}
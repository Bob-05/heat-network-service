package ru.hackathon.heatnetworkservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.hackathon.heatnetworkservice.model.TieIn;

import java.util.List;

@Repository
public interface TieInRepository extends JpaRepository<TieIn, String> {

    List<TieIn> findByVariantId(String variantId);

    List<TieIn> findByExistingObjectId(String existingObjectId);

    List<TieIn> findByExistingObjectType(String existingObjectType);
}
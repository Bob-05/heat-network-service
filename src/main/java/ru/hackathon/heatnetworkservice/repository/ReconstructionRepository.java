package ru.hackathon.heatnetworkservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.hackathon.heatnetworkservice.model.Reconstruction;

import java.util.List;

@Repository
public interface ReconstructionRepository extends JpaRepository<Reconstruction, String> {

    List<Reconstruction> findByVariantId(String variantId);

    List<Reconstruction> findByExistingObjectId(String existingObjectId);
}
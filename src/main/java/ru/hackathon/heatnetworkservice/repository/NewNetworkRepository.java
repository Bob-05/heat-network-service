package ru.hackathon.heatnetworkservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.hackathon.heatnetworkservice.model.NewNetwork;

import java.util.List;

@Repository
public interface NewNetworkRepository extends JpaRepository<NewNetwork, String> {

    List<NewNetwork> findByVariantId(String variantId);

    List<NewNetwork> findByDiameter(Integer diameter);

    List<NewNetwork> findByLayingMethod(String layingMethod);
}
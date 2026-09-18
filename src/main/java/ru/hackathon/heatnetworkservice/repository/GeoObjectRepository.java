package ru.hackathon.heatnetworkservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.List;

@Repository
public interface GeoObjectRepository extends JpaRepository<GeoObject, String> {

    List<GeoObject> findByObjectType(String objectType);

    List<GeoObject> findByObjectTypeIn(List<String> objectTypes);

    List<GeoObject> findByObjectTypeAndRestrictionType(String objectType, String restrictionType);

    @Query("SELECT g FROM GeoObject g WHERE g.objectType = :type")
    List<GeoObject> findCustom(@Param("type") String type);

    @Query(value = "SELECT * FROM geo_objects WHERE ST_Intersects(geometry, ST_GeomFromText(:wkt, 4326))",
            nativeQuery = true)
    List<GeoObject> findIntersecting(@Param("wkt") String wkt);
}
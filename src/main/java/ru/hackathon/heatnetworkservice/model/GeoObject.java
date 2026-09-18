package ru.hackathon.heatnetworkservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Geometry;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "geo_objects")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeoObject {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "object_type", nullable = false)
    private String objectType;

    @Column(name = "restriction_type")
    private String restrictionType;

    @Column(name = "diameter")
    private Integer diameter;

    @Column(name = "flow_tph")
    private Double flowTph;

    @Column(name = "heat_load")
    private Double heatLoad;

    @Column(name = "oks_id")
    private String oksId;

    @Column(name = "upstream_object_id")
    private String upstreamObjectId;

    @Column(name = "properties", columnDefinition = "jsonb")
    private String properties;

    @Column(name = "geometry", columnDefinition = "geometry(Geometry, 4326)")
    private Geometry geometry;
}
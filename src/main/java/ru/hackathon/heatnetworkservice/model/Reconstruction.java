package ru.hackathon.heatnetworkservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.LineString;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "reconstructions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Reconstruction {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "variant_id", nullable = false)
    private String variantId;

    @Column(name = "existing_object_id", nullable = false)
    private String existingObjectId;

    @Column(name = "existing_flow_tph")
    private Double existingFlowTph;

    @Column(name = "added_flow_tph")
    private Double addedFlowTph;

    @Column(name = "calculated_flow_tph")
    private Double calculatedFlowTph;

    @Column(name = "existing_diameter")
    private Integer existingDiameter;

    @Column(name = "required_diameter", nullable = false)
    private Integer requiredDiameter;

    @Column(name = "length", nullable = false)
    private Double length;

    @Column(name = "cost", nullable = false)
    private Double cost;

    @Column(name = "geometry", columnDefinition = "geometry(LineString, 4326)")
    private LineString geometry;
}
package ru.hackathon.heatnetworkservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "tie_ins")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TieIn {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "variant_id", nullable = false)
    private String variantId;

    @Column(name = "existing_object_id", nullable = false)
    private String existingObjectId;

    @Column(name = "existing_object_type", nullable = false)
    private String existingObjectType;

    @Column(name = "existing_diameter")
    private Integer existingDiameter;

    @Column(name = "required_diameter", nullable = false)
    private Integer requiredDiameter;

    @Column(name = "cost", nullable = false)
    private Double cost;

    @Column(name = "geometry", columnDefinition = "geometry(Point, 4326)")
    private Point geometry;
}

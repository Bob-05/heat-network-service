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
@Table(name = "new_networks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewNetwork {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "variant_id", nullable = false)
    private String variantId;

    @Column(name = "start_node_id", nullable = false)
    private String startNodeId;

    @Column(name = "end_node_id", nullable = false)
    private String endNodeId;

    @Column(name = "flow_tph", nullable = false)
    private Double flowTph;

    @Column(name = "diameter", nullable = false)
    private Integer diameter;

    @Column(name = "length", nullable = false)
    private Double length;

    @Column(name = "laying_method", nullable = false)
    private String layingMethod;

    @Column(name = "depth_start")
    private Double depthStart;

    @Column(name = "depth_end")
    private Double depthEnd;

    @Column(name = "cost", nullable = false)
    private Double cost;

    @Column(name = "geometry", columnDefinition = "geometry(LineString, 4326)")
    private LineString geometry;
}
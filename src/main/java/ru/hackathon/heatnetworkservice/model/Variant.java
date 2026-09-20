package ru.hackathon.heatnetworkservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "variants")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Variant {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "construction_cost")
    private Double constructionCost;

    @Column(name = "chamber_construction_cost")
    private Double chamberConstructionCost;

    @Column(name = "existing_chamber_tie_in_count")
    private Integer existingChamberTieInCount;

    @Column(name = "existing_chamber_tie_in_cost")
    private Double existingChamberTieInCost;

    @Column(name = "unconnected_penalty")
    private Double unconnectedPenalty;

    @Column(name = "calculated_cost")
    private Double calculatedCost;

    @Column(name = "new_network_length")
    private Double newNetworkLength;

    @Column(name = "score")
    private Double score;

    @Column(name = "unconnected_oks_ids", columnDefinition = "text")
    private String unconnectedOksIds;
}
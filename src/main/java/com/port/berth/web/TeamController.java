package com.port.berth.web;

import com.port.berth.repo.PlanDao;
import com.port.berth.service.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 现场班组看板：只读取 CONFIRMED 方案——暂存/候选不会对班组生效。
 */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final PlanDao planDao;
    private final PlanService planService;

    public TeamController(PlanDao planDao, PlanService planService) {
        this.planDao = planDao;
        this.planService = planService;
    }

    @GetMapping("/board")
    public Map<String, Object> board() {
        List<Map<String, Object>> pilotCards = new ArrayList<>();
        List<Map<String, Object>> tugCards = new ArrayList<>();
        List<Map<String, Object>> craneCards = new ArrayList<>();
        List<Map<String, Object>> berthCards = new ArrayList<>();

        List<PlanDao.PlanRow> confirmed = planDao.listPlansByStatus("CONFIRMED");
        for (PlanDao.PlanRow p : confirmed) {
            Map<String, Object> detail = planService.planDetail(p.id);
            String voyageNo = lookupVoyageNo(p.voyageId);
            String shipName = lookupShipName(p.voyageId);
            String berthCode = (String) detail.get("berthCode");

            @SuppressWarnings("unchecked")
            List<PlanDao.SegmentRow> segs = (List<PlanDao.SegmentRow>) detail.get("segments");
            for (PlanDao.SegmentRow s : segs) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("planId", p.id);
                card.put("planNo", p.planNo);
                card.put("voyageId", p.voyageId);
                card.put("voyageNo", voyageNo);
                card.put("shipName", shipName);
                card.put("berthCode", berthCode);
                card.put("segmentId", s.id);
                card.put("segType", s.segType);
                card.put("title", s.title);
                card.put("resourceCode", s.resourceCode);
                card.put("startAt", s.startAt);
                card.put("endAt", s.endAt);
                card.put("status", s.status);
                card.put("affected", s.affected);
                card.put("affectReason", s.affectReason);

                switch (s.segType) {
                    case "PILOTAGE" -> pilotCards.add(card);
                    case "TUG_INBOUND", "TUG_OUTBOUND" -> tugCards.add(card);
                    case "BERTHING", "UNBERTHING" -> berthCards.add(card);
                    default -> { }
                }
            }

            // 岸桥按 assignments 展开成每台岸桥一张作业卡
            PlanDao.SegmentRow cargoSeg = planDao.listSegments(p.id).stream()
                    .filter(s -> "CARGO_OP".equals(s.segType)).findFirst().orElse(null);
            Object assignmentsObj = detail.get("assignments");
            if (assignmentsObj instanceof List<?> assignments) {
                for (Object a : assignments) {
                    if (a instanceof Map<?, ?> am && "CRANE".equals(am.get("resourceType"))) {
                        Map<String, Object> card = new LinkedHashMap<>();
                        card.put("planId", p.id);
                        card.put("planNo", p.planNo);
                        card.put("voyageId", p.voyageId);
                        card.put("voyageNo", voyageNo);
                        card.put("shipName", shipName);
                        card.put("berthCode", berthCode);
                        card.put("craneCode", am.get("code"));
                        card.put("craneName", am.get("name"));
                        card.put("opsStart", p.opsStart);
                        card.put("opsEnd", p.opsEnd);
                        card.put("status", "SCHEDULED");
                        if (cargoSeg != null) {
                            card.put("segmentId", cargoSeg.id);
                            card.put("segType", cargoSeg.segType);
                            card.put("segmentStatus", cargoSeg.status);
                            card.put("status", cargoSeg.status);
                            card.put("recordId", cargoSeg.recordId);
                            card.put("affected", cargoSeg.affected);
                            card.put("affectReason", cargoSeg.affectReason);
                        }
                        craneCards.add(card);
                    }
                }
            }

            // 泊位占用卡（一个方案一张）
            Map<String, Object> berthCard = new LinkedHashMap<>();
            berthCard.put("planId", p.id);
            berthCard.put("planNo", p.planNo);
            berthCard.put("voyageNo", voyageNo);
            berthCard.put("shipName", shipName);
            berthCard.put("berthCode", berthCode);
            berthCard.put("berthFrom", p.berthFrom);
            berthCard.put("berthTo", p.berthTo);
            berthCards.add(berthCard);
        }

        pilotCards.sort((a, b) -> ((java.time.LocalDateTime) a.get("startAt"))
                .compareTo((java.time.LocalDateTime) b.get("startAt")));
        tugCards.sort((a, b) -> ((java.time.LocalDateTime) a.get("startAt"))
                .compareTo((java.time.LocalDateTime) b.get("startAt")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("effectivePlans", confirmed.size());
        result.put("notice", "仅 CONFIRMED 方案出现在班组看板；候选与暂存不对现场生效");
        result.put("pilot", pilotCards);
        result.put("tug", tugCards);
        result.put("crane", craneCards);
        result.put("berth", berthCards);
        return result;
    }

    private String lookupVoyageNo(long voyageId) {
        List<String> r = planDao.jdbc().queryForList(
                "SELECT voyage_no FROM voyage WHERE id=?", String.class, voyageId);
        return r.isEmpty() ? "#" + voyageId : r.get(0);
    }

    private String lookupShipName(long voyageId) {
        List<String> r = planDao.jdbc().queryForList("""
                SELECT s.name FROM voyage v JOIN ship s ON s.id=v.ship_id WHERE v.id=?
                """, String.class, voyageId);
        return r.isEmpty() ? "" : r.get(0);
    }
}

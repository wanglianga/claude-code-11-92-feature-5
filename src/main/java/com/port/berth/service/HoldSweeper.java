package com.port.berth.service;

import com.port.berth.repo.PlanDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**每秒扫描到期的 STAGED 暂留并释放资源*/
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    private final PlanDao planDao;
    private final PlanService planService;

    public HoldSweeper(PlanDao planDao, PlanService planService) {
        this.planDao = planDao;
        this.planService = planService;
    }

    @Scheduled(fixedDelay = 500L)
    public void sweep() {
        for (PlanDao.PlanRow p : planDao.listPlansByStatus("STAGED")) {
            try {
                if (planService.expireIfDue(p.id)) {
                    log.info("暂留 TTL 到期，方案 {} 资源已释放", p.planNo);
                }
            } catch (Exception ex) {
                // 单条失败不影响下一轮扫描
                log.warn("释放过期暂留失败 planId={}: {}", p.id, ex.getMessage());
            }
        }
    }
}

package com.port.berth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 用 JdbcTemplate 下发 plpgsql 函数/触发器（schema.sql 经脚本切分器执行，
 * 不能可靠处理 $$ 美元引用）。幂等，每次启动重建。
 */
@Component
@Order(0)
public class DbTriggerInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbTriggerInitializer.class);

    private final JdbcTemplate jdbc;

    public DbTriggerInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1) 已完成作业记录不可变 ----------------------------------------
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fn_work_record_immutable() RETURNS trigger AS $fn$
                BEGIN
                    RAISE EXCEPTION 'WORK_RECORD_IMMUTABLE: completed record cannot be changed or deleted (id=%)',
                        COALESCE(OLD.id, NEW.id) USING ERRCODE = '45000';
                END;
                $fn$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_work_record_immutable ON work_record");
        jdbc.execute("""
                CREATE TRIGGER trg_work_record_immutable
                    BEFORE UPDATE OR DELETE ON work_record
                    FOR EACH ROW EXECUTE FUNCTION fn_work_record_immutable()
                """);

        // 2) 资源占用互斥：资源锁父行 FOR UPDATE 串行化 + 区间重叠检查 ----
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fn_occupation_overlap_guard() RETURNS trigger AS $fn$
                BEGIN
                    IF NEW.status = 'ACTIVE' THEN
                        INSERT INTO resource_lock(resource_key)
                        VALUES (NEW.resource_key)
                        ON CONFLICT (resource_key) DO NOTHING;

                        PERFORM 1 FROM resource_lock rl
                        WHERE rl.resource_key = NEW.resource_key
                        FOR UPDATE;

                        IF EXISTS (
                            SELECT 1 FROM resource_occupation o
                            WHERE o.status = 'ACTIVE'
                              AND o.resource_key = NEW.resource_key
                              AND o.id IS DISTINCT FROM NEW.id
                              AND o.start_at < NEW.end_at
                              AND o.end_at   > NEW.start_at
                        ) THEN
                            RAISE EXCEPTION 'RESOURCE_OVERLAP: % [% , %) overlaps existing occupation',
                                NEW.resource_key, NEW.start_at, NEW.end_at
                                USING ERRCODE = '23P01';
                        END IF;
                    END IF;
                    RETURN NEW;
                END;
                $fn$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_occupation_overlap ON resource_occupation");
        jdbc.execute("""
                CREATE TRIGGER trg_occupation_overlap
                    BEFORE INSERT OR UPDATE OF status, start_at, end_at, resource_key
                    ON resource_occupation
                    FOR EACH ROW EXECUTE FUNCTION fn_occupation_overlap_guard()
                """);

        // 3) 滞期回执不可变：双方凭证只增不改不删 --------------------------
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fn_laytime_event_immutable() RETURNS trigger AS $fn$
                BEGIN
                    RAISE EXCEPTION 'LAYTIME_EVENT_IMMUTABLE: receipt cannot be changed or deleted (id=%)',
                        COALESCE(OLD.id, NEW.id) USING ERRCODE = '45000';
                END;
                $fn$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_laytime_event_immutable ON laytime_event");
        jdbc.execute("""
                CREATE TRIGGER trg_laytime_event_immutable
                    BEFORE UPDATE OR DELETE ON laytime_event
                    FOR EACH ROW EXECUTE FUNCTION fn_laytime_event_immutable()
                """);
        log.info("数据库触发器初始化完成（作业记录不可变、资源占用互斥、滞期回执不可变）");
    }
}

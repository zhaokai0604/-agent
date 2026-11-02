package com.award.log.integration;

import com.award.log.agent.team.*;
import com.award.log.agent.team.TeamProtocol.*;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.OpsRecycleBinService;
import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.McpToolSurface;
import com.award.log.util.OsRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real model + real policy/dispatcher/commands + actual dedicated canary only. */
@EnabledIfSystemProperty(named="thresh.team-host-live",matches="true")
@SpringBootTest(properties={"ops.dry-run.global=false","ops.pep.enabled=true",
        "agent.min-privilege.enabled=false","agent.teams.active-budget-ms=300000"})
class AgentTeamHostLiveIT extends IntegrationTestBase {
    static Path root;
    static final Path workspaceTarget=Path.of("target").toAbsolutePath().normalize();
    @Autowired AgentTeamService teams;
    @Autowired TeamRunStore store;
    @Autowired TeamRoleModel roles;
    @Autowired com.award.log.mcp.MinPrivilegeExecutor commands;
    @Autowired com.award.log.service.mcp.McpExecutionService dispatch;
    @Autowired com.award.log.service.mcp.McpSecurityService security;
    @Autowired TeamDataPolicy data;
    @Autowired OpsPathPolicy paths;
    @Autowired OpsRecycleBinService recycle;
    @Autowired OpsExecutionPipeline pipeline;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    String id;
    Path canary;
    boolean restored;
    Map<String,Object> recoveryReceipt=Map.of();

    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(workspaceTarget);
        root=Files.createTempDirectory(workspaceTarget,"team-host-live-").toAbsolutePath().normalize();
        registry.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:team_host_live;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE");
        registry.add("spring.datasource.username",()->"sa");
        registry.add("spring.datasource.password",()->"test");
        registry.add("spring.sql.init.mode",()->"never");
        registry.add("spring.flyway.enabled",()->"true");
        registry.add("spring.flyway.baseline-on-migrate",()->"false");
        registry.add("knowledge.enabled",()->"false");
        registry.add("knowledge.seed-on-startup",()->"false");
        registry.add("qdrant.url",()->"http://127.0.0.1:9");
        registry.add("qdrant.api-key",()->"");
        registry.add("agent.awm.seed-on-startup",()->"false");
        registry.add("agent.recycle.root",()->root.resolve("recycle").toString());
        registry.add("agent.recycle.volume-roots",()->"");
        // Load only AI properties from explicit environment or existing local configuration.
        // Developer database/vector credentials are never imported into this test context.
        var sources=new MutablePropertySources();
        sources.addLast(new SystemEnvironmentPropertySource("environment",new HashMap<>(System.getenv())));
        var local=new FileSystemResource("config/application-local.yml");
        if(local.exists())for(var source:new YamlPropertySourceLoader().load("local-ai",local))sources.addLast(source);
        var resolver=new PropertySourcesPropertyResolver(sources);
        String key=first(System.getenv("AI_API_KEY"),System.getenv("OPENAI_API_KEY"),resolver.getProperty("spring.ai.openai.api-key"));
        String url=first(System.getenv("AI_BASE_URL"),resolver.getProperty("spring.ai.openai.base-url"));
        String model=first(System.getenv("AI_MODEL"),resolver.getProperty("spring.ai.openai.chat.options.model"));
        if(key.isBlank()||url.isBlank()||model.isBlank())throw new IllegalStateException("Live AI key/base-url/model configuration is required");
        registry.add("spring.ai.openai.api-key",()->key);
        registry.add("spring.ai.openai.base-url",()->url);
        registry.add("spring.ai.openai.chat.options.model",()->model);
    }
    static String first(String... choices){return Arrays.stream(choices).filter(s->s!=null&&!s.isBlank()).findFirst().orElse("");}
    @BeforeEach void prepare() throws Exception {
        for(Object component:List.of(teams,roles,commands,dispatch,security,pipeline,recycle)) {
            assertFalse(org.mockito.Mockito.mockingDetails(component).isMock(),"Real acceptance cannot use mocked core components");
        }
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        paths.updateEditablePathLists(OsRuntime.isWindows(),List.of(root.toString()),List.of(root.toString()),List.of());
        canary=Files.createDirectory(root.resolve("canary"));
        Files.writeString(canary.resolve("sample.log"),"dedicated canary payload\n");
    }
    TeamRunStore.Run terminal(String... states){
        await().atMost(Duration.ofSeconds(160)).until(()->Set.of(states).contains(store.get(id).state()));
        return store.get(id);
    }
    @Test @Timeout(330) void realRolesExecuteAndRecoverTheExactApprovedCanary() throws Exception {
        String target=canary.toString();
        String goal="本机隔离验收：仅处置专用测试子目录 "+target
                +"。这是测试者明确要求的目录回收任务，不要求降低整个磁盘的使用百分比。"
                +"仅规划一个 CleanTempTool 步骤，path 必须逐字使用该目录，days=0，removeDirectory=true。"
                +"目录内只有测试生成文件；批准前不能写入，执行后应移入回收站。验证使用 DiskTool 只读观测并结合管线的对象验证回执，"
                +"小样本不会显著改变磁盘百分比。不要扩展到其他目录或编造观测。";
        id=teams.start(7,UUID.randomUUID().toString(),goal,"temp-cleanup",List.of(
                new Probe("DiskTool",Map.of()),new Probe("CleanTempTool",Map.of("path",target,"days",0,"removeDirectory",true)))).id();
        var ready=terminal("WAITING_APPROVAL","NEEDS_HUMAN");
        assertEquals("WAITING_APPROVAL",ready.state(),()->"state="+ready.state()+", error="+ready.error());
        Plan plan=data.decode(ready.planJson(),Plan.class);
        assertEquals(1,plan.steps().size(),"Test only authorizes a single exact canary step");
        Step step=plan.steps().get(0);
        assertEquals("CleanTempTool",step.tool());
        assertEquals(canary,Path.of(String.valueOf(step.parameters().get("path"))).toAbsolutePath().normalize());
        assertEquals(Boolean.TRUE,step.parameters().get("removeDirectory"));
        assertEquals(0,((Number)step.parameters().get("days")).intValue());
        assertTrue(plan.verification().stream().allMatch(p->"DiskTool".equals(p.tool())));
        assertEquals("dedicated canary payload\n",Files.readString(canary.resolve("sample.log")));
        assertTrue(recycle.listEntries().isEmpty());
        teams.approve(id,ready.revision(),ready.planHash(),"确认执行","7");
        var done=terminal("COMPLETED","NEEDS_HUMAN");
        assertEquals("COMPLETED",done.state(),()->"state="+done.state()+", error="+done.error());
        assertEquals(3,done.modelCalls());
        assertFalse(Files.exists(canary));
        var entries=recycle.listEntries();assertEquals(1,entries.size());
        assertEquals(canary.toString(),entries.get(0).get("originalPath"));
        // Recovery uses the same real preview, confirmation and verification pipeline.
        String handle=String.valueOf(entries.get(0).get("handle"));
        OpsSecurityContext.open(id,"恢复隔离测试样本",false,McpToolSurface.FULL,false,false,false,"7",id);
        try {
            var params=Map.<String,Object>of("operation","restore","handle",handle,"dryRun",false);
            var preview=pipeline.preview("RecycleBinTool",params,"恢复隔离测试样本","7");
            assertEquals(true,preview.body().get("success"));
            var proposal=pipeline.propose("RecycleBinTool",params,"恢复隔离测试样本","7",preview.dryRunPreviewJson(),id);
            assertEquals(true,proposal.body().get("needConfirm"));
            var ticket=proposal.body();
            recoveryReceipt=pipeline.executeConfirmed(String.valueOf(ticket.get("confirmationId")),
                    String.valueOf(ticket.get("capabilityToken")),String.valueOf(ticket.get("confirmToken")),"确认执行","7",id).body();
            assertEquals(true,recoveryReceipt.get("success"));
            assertEquals("VERIFIED",recoveryReceipt.get("verifyOutcome"));
        } finally {OpsSecurityContext.clear();}
        restored="dedicated canary payload\n".equals(Files.readString(canary.resolve("sample.log")));
        assertTrue(restored);
        for(String role:List.of("DIAGNOSIS","PLANNER","VERIFIER"))
            assertTrue(store.events(id).stream().anyMatch(e->role.equals(e.get("actor"))&&"MODEL_OUTPUT".equals(e.get("kind"))));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ops_audit_trace WHERE tool_name='CleanTempTool'",Integer.class)>0);
    }
    @AfterEach void evidenceAndRecovery() throws Exception {
        try {
            if(id!=null){
                var run=store.get(id);
                var evidence=new LinkedHashMap<String,Object>();
                evidence.put("scenario","real-model-real-pipeline-dedicated-canary");
                evidence.put("runId",id);evidence.put("state",run.state());
                evidence.put("error",run.error()==null?"":run.error());evidence.put("modelCalls",run.modelCalls());
                evidence.put("restoredAndContentMatched",restored);evidence.put("events",store.events(id));
                evidence.put("recoveryReceipt",recoveryReceipt);
                Path report=workspaceTarget.resolve("experiments/agent-team-host-live.json");
                Files.createDirectories(report.getParent());Files.writeString(report,data.safe(evidence));
                teams.cancel(id,"TEST_CLEANUP");
            }
        } finally {
            // Recovery precedes deletion of any test artifact, including assertion failures.
            for(var entry:recycle.listEntries())assertTrue(recycle.restore(String.valueOf(entry.get("handle"))),"Canary recovery failed");
        }
    }
}

---
canonical: docs/summary/2026-11/2026-11-19_1200_bug-7-send-contract-and-child-failure-surfacing.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:11:31+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1200_bug-7-send-contract-and-child-failure-surfacing
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 290
  passRate: "100%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-7 派发返回 runId 契约与子会话失败可见

## 概述
BUG-7锛堝伐浣滃尯浼氳瘽涓㈠け + 瀛?agent 鏃犳硶姝ｅ父宸ヤ綔锛夌殑鍚庡崐锛氳繘鎵ц璇︽儏澶嶆牳鏃剁‘璁や簡涓€涓湡瀹炲绾︾己闄封€斺€擟onversationManager.send 鍦ㄣ€岀洰鏍囨槸璋冨害浼氳瘽鏈韩銆嶄笌銆屽悓浼氳瘽宸叉湁鐩稿悓鐩爣杩愯銆嶄袱鏉″垎鏀笂杩斿洖涓€鍙?err 鏂囨锛岃€屾帴鍙ｆ敞閲娿€丠TTP 灞傦紙Map.of runId 鍖呰锛変笌 DAG 娲惧彂鍣ㄩ兘鎶婅繖涓繑鍥炲€煎綋 runId 浣跨敤銆傜粨鏋滄槸瀛愪細璇濇嬁鍒颁竴鐗囨枃鏈綋 id銆侀殢鍗崇瓑涓€涓笉瀛樺湪鐨勮繍琛岋紝琛ㄧ幇涓恒€屾淳鍙戞垚鍔熶絾姘歌繙涓嶅姩銆嶏紝鐖朵細璇濅篃鎷夸笉鍒板け璐ュ師鍥狅紱娲惧彂鍣ㄩ噷閭ｆ鍒ゆ柇杩斿洖鍊兼槸鍚︿互 err 寮€澶寸殑浠ｇ爜锛屽氨鏄繖涓己闄风暀涓嬬殑鐥曡抗銆?
send 鎭㈠涓哄崟涓€濂戠害锛氳涔堣繑鍥炲悎娉?runId锛岃涔堟姏 RunException锛堥噸澶嶇洰鏍囩敤 RUN_INVALID_STATE锛岃涔夋槸鐘舵€佸啿绐侊紱娲惧彂鍒拌皟搴︿細璇濈敤 RUN_INVALID_INPUT锛夈€傛淳鍙戝櫒涓嶅啀闈犻敊璇墠缂€鐚滃唴瀹癸紝鏀逛负鏍￠獙 runId 褰㈢姸锛坮un- 鍓嶇紑涓旈潪绌猴級锛屽舰鐘朵笉瀵圭洿鎺ユ姤銆屽绾︾己闄枫€嶅苟鍥炴樉杩斿洖鍊硷紝閬垮厤涓嬫鍐嶈鏂囨湰鍐掑厖鎴?id銆俉orkspaceSupervisorPort#send 琛ヤ笂濂戠害璇存槑锛涙棦鏈夌敤渚嬫敼涓烘柇瑷€鎶涘紓甯革紝骞舵柊澧?DispatchSendContractTest 閿佷綇鍥涙潯锛氶潪 runId 鍒ゅけ璐ャ€佺┖鍊煎垽澶辫触涓斾笉杩涘叆绛夊緟銆佸悎娉?runId 姝ｅ父绛夊緟銆乻end 鎶涘紓甯告椂鎶婁笟鍔″師鍥犻€忎紶鎴愯妭鐐瑰け璐ャ€?

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `5f71090` | bug | fix(workspace): send 只返回 runId 或抛异常，不再返回错误文案 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-gateway,agent-runtime,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-gateway,agent-runtime test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 290, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：4
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1200_bug-7-send-contract-and-child-failure-surfacing/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask

# Example task batch

- [ ] **T01 — Update module A.** Make and verify the requested change in module A.
  <!-- codex-task: parallel-safe=true -->
- [ ] **T02 — Update module B.** Make and verify the independent change in module B.
  <!-- codex-task: parallel-safe=true -->
- [ ] **T03 — Verify integration.** Verify the combined result after T01 and T02 are integrated.
  <!-- codex-task: depends-on=T01,T02 -->

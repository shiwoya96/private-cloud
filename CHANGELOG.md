# Changelog

## Unreleased

- 未加密的新快照在 NAS 上保留原目录、原文件名和 WebDAV MIME 类型；旧 `.blob` 快照仍可恢复。

## 0.2.0

- 增加多方案、自动备份、排除规则、任务历史、快照保留与选择性恢复。
- 增加可选的恢复密钥 AES-256-GCM 文件内容加密。
- 增加更严格的取消、重试、缓存刷新和 SAF 授权清理流程。
- 增加 GitHub Secrets release 签名及基于版本标签的 GitHub Release 发布。

## 0.1.0

- 初始测试版本，支持 WebDAV、SMB、SAF 目录、手动备份、快照验证与安全恢复。

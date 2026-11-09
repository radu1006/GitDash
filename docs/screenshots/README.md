# Screenshots

Placeholders referenced by [README.md](../../README.md#screenshots). Nothing is committed here yet —
capture the shots from a debug build on a real device or an API 26+ emulator (light theme keeps the
palette consistent) and commit them with these names:

| File | Screen |
| --- | --- |
| `01-onboarding.png` | Token onboarding, empty state |
| `02-token-verified.png` | "Verified as …" confirmation before continuing |
| `03-repo-list.png` | Repository list with a private badge and stars |
| `04-repo-filter.png` | Name filter with matches |
| `05-repo-detail.png` | Repository detail header, stats, actions |
| `06-settings.png` | Settings with a masked saved token |
| `07-error.png` | Error pane (rate limit / network) |
| `08-file-browser.png` | File browser: path bar, folder/file rows, file sizes, "Open on GitHub" |
| `09-code-viewer.png` | Code viewer: highlighted file with line-number gutter, Open-on-GitHub action |
| `10-readme-viewer.png` | README viewer: rendered headings, list, code block and an embedded screenshot |

Guidelines:

- Use a test account with at least one public and one private repository, and no secrets in the
  description fields.
- **Never** screenshot the token entry field with a real token in it — the value is masked, but a
  partial paste can still be visible.
- Keep PNGs at device resolution (no heavy downscaling) and avoid status-bar clutter that reveals
  personal information.

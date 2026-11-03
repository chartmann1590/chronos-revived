# Chronos disc: reverse-engineered protocol

Recovered from the last official Android app (`com.wearchronos.chronos` v1.0, May 2017) and its bundled firmware, then verified against a real disc.

## Hardware (from firmware disassembly)

- nRF51 SoC with SoftDevice S110 (`softdevice_req` 0x64); application at `0x18000`.
- Kionix accelerometer on I²C `0x1E` (WHO_AM_I reg `0x0F`): steps and taps.
- TI DRV2605 haptic driver on I²C `0x5A`; its enable pin is P0.12. Vibration "patterns" are DRV2605 library effect IDs.
- I²C: SCL P0.09, SDA P0.08, 100 kHz.
- Battery voltage: ADC AIN7, `mV = raw * 0x4571 >> 12`, averaged over 4 samples.
- Charger: standalone chip. P0.07 = power present (status 0x0004). "Charging" (status 0x0002) is inferred by timing pulses
  on the charger's status line (period 2328–3309 RTC ticks). **The firmware has no way to enable or force charging**;
  the charger chip decides on its own, from the cell voltage.

## Advertising

- App mode: name `Chronos`, services 0x1814 + Nordic UART, ANCS solicitation. Only advertises for a short while after it's moved.
- Bootloader mode: name `Chronos`, service `beb81530-…`. Address = app address **+1** (low 16 bits).

## GATT (app mode)

| Service / characteristic | UUID | Use |
|---|---|---|
| Chronos service | `beb81000-b1b1-5b9d-b4a0-c030b58bc170` | |
| Status | `beb81001-…` | read/notify, u16 LE bitfield |
| Command | `beb81002-…` | write with response |
| Response | `beb81003-…` | notify |
| Battery | `0x180F / 0x2A19` | read/notify, percent |
| Firmware revision | `0x180A / 0x2A26` | ASCII, e.g. `0.7.48` |
| Also present | `0x1812` HID, `0x1530` Nordic DFU, Nordic UART | |

Subscribing to notifications needs an encrypted link: the disc requests pairing (Just Works confirmation) and
drops the link if it isn't accepted within ~30 s.

### Status bits

| Bit | Meaning |
|---|---|
| 0x0001 | busy (wait before next command) |
| 0x0002 | charging |
| 0x0004 | on charger (power present) |
| 0x0008 | face tapped repeatedly (find phone, held while tapping) |
| 0x0010 / 0x0020 | LED off / LED low |
| 0x0200 | face double tap |
| 0x2000 / 0x4000 | crown double / triple tap |

### Commands (`opcode, length, payload…`)

| Bytes | Meaning |
|---|---|
| `97 01 LL` | LED brightness (app sends FF on connect) |
| `9C 00` | current steps → `9C ?? AAAA BBBB` (BE u16 each, total = A+B) |
| `9D 00` | next history record → `9D NN ?? TTTTTTTT AAAA BBBB`; NN counts down to 1, T/20 = seconds ago |
| `B0..B3 01 EN` | enable taps for silence-call / camera / media / find-phone; replies `B0 LL HH` bitmask |
| `91 11 61 01 P C FF 2F Q C 04 2F 02 C 02 2F 02 20 Q` | LED program. C = 0x10 \| R<<3 \| G<<2 \| B<<1; P = 0x80+pulses; Q = 18/06/03 for 1/2/3 pulses |
| `90 0A 40 01 e1 e2 e3 …` | vibration program: DRV2605 effect IDs (`2F 2F`, `0E 8F 0E`, `31 82 31 82 31`) |
| `95 04 61 01 40 01` | play the programmed LED + vibration now |

| `A0 01 EN` | extended status mode (EN=1), unused by the official app; see below |

Further opcodes handled by firmware but unused by the official app: `70` (clears step storage), `71 72 92 96`,
`9E` (`reset_databases`).

### Extended status (`A0 01 01`)

Status notifications grow from 2 to 8 bytes and are pushed whenever they change:

| Bytes | Meaning |
|---|---|
| 0–1 | status bits as above |
| 2–3 | battery millivolts, BE: 4-sample mean of `raw * 0x4571 >> 12` (the same value the 0x2A19 percentage comes from) |
| 4–5 | value from the accelerometer driver (not decoded) |
| 6–7 | latest raw battery ADC count, BE |

On the test disc this reported `00 1E … 00 07`: 7 ADC counts, **30 mV**. The cell was effectively at 0 V, which is
why it read 0% and the charger chip never started (Li-ion chargers won't charge a cell that flat).

## Firmware update

Nordic legacy DFU with vendor UUIDs `beb81530/1531/1532/1534-b1b1-5b9d-b4a0-c030b58bc170`.
Image: `app/src/main/res/raw/firmware.zip` (0.7.48, the newest ever shipped). PRN 12, no keep-bond.

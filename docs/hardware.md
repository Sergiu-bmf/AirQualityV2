# Hardware

An Arduino Uno R3 and a breadboard, built from an "Upgraded Learning Kit" plus one
separately-purchased gas sensor. Only a subset of the kit is used.

![The full breadboard](images/hardware-overview.jpg)

## Components in use

| Component | Purpose | Pin | Source |
|---|---|---|---|
| Arduino Uno R3 | Main board |, | Kit |
| DHT11 | Temperature + humidity | `D2` | Kit |
| Sound sensor (HW-484, KY-038 family) | Sound level | `A0` | Kit |
| Photoresistor (LDR) | Ambient light | `A1` | Kit, bare component, needs a resistor |
| MQ-135 | Gas / air quality | `A2` | Purchased separately |
| Flame sensor (bare 2-leg phototransistor) | Flame detection | `A5` | Kit, bare component, needs a resistor |
| 3 × LED (green / yellow / red) | Traffic-light status | `D4` `D5` `D6` | Kit |
| Buzzer | Audible alarm | `D7` | Kit |
| 9V battery + barrel connector | Standalone power (unused) | Power jack | Kit |

<div class="grid cards" markdown>

-   ![Status LEDs and buzzer](images/hardware-leds-buzzer.jpg)

    The traffic light and buzzer, the only part that works with no computer attached

-   ![MQ-135 gas sensor](images/hardware-mq135.jpg)

    The MQ-135, added after the rest of the build

-   ![Sound sensor](images/hardware-sound-sensor.jpg)

    The HW-484 sound sensor module

</div>

## Resistors

Identified by colour band from an unlabelled kit assortment, confirm with a multimeter if
in doubt:

- **~100 Ω**, current limiting for the three status LEDs
- **~1 kΩ**, spare
- **~10 kΩ**, voltage dividers for the LDR and the flame sensor

## Wiring

**LDR**, `5V → LDR → tap to A1 → 10 kΩ → GND`. Higher raw ADC means more light. This
orientation is what makes the resistance formula in [Calibration](calibration.md#light-ldr)
valid; reversing the divider inverts it.

**Flame sensor**, a bare component, *not* a pre-built digital module. Long leg (anode) to
`5V`; short leg (cathode) to `A5` **and** through 10 kΩ to `GND`.

!!! warning "The legs need swapping from the 'standard' orientation"

    Wired per the usual long-leg/short-leg convention this produced a flat, unusable signal
    with a 0→10 swing. Swapping the legs gave a usable one: ~3 at rest, ~700 with a flame
    held close. See the [debugging log](debugging-log.md).

**LEDs**: each needs its own ~100 Ω resistor between the Arduino pin and the anode.

**Buzzer, sound and gas modules**, pre-built, no external resistor. VCC / GND / signal only.

!!! danger "This breadboard has a split ground rail"

    The power and ground rails are physically two disconnected halves. Components on
    opposite halves do **not** share ground unless the halves are bridged with a jumper.
    This cost a long debugging detour that looked exactly like a floating-pin fault, see
    [entry 9 in the debugging log](debugging-log.md).

## Standalone operation

Researched, not implemented. The board currently depends on a laptop for both storage and
alerting.

- **Flash (32 KB)** holds the compiled sketch permanently; the Arduino does not need a
  computer to *run*, only to record.
- **SRAM (2 KB)** is volatile working memory, lost on power cut.
- **EEPROM (1 KB)** is too small and too write-limited for continuous logging.
- **For standalone logging** an SD module (SPI, `SD.h`) is needed. A 2–8 GB card is ample at
  roughly 35 KB/day; format FAT32 and avoid cards over 32 GB to sidestep exFAT issues with
  the basic library.

    !!! note "SD logging existed and was removed"

        The sketch previously wrote `data.csv` to an SD module. That code has been removed;
        a dead `#define SD_CS_PIN 10` is all that remains.

- **For standalone networking** you would need an ESP8266 shield, a switch to ESP32,
  Bluetooth (short range), or GSM/LoRa for remote sites.
- **Power**, the 9V battery works but holds only ~400–600 mAh. Expect hours, not days,
  especially with the LEDs and buzzer active. A LiPo pack would be the better choice.
- **RTC**, available in the kit, deliberately not added. The pipeline timestamps from the
  laptop's NTP-synced clock, and the whole system currently requires that laptop anyway.
  Without an RTC the board only knows time since power-on.

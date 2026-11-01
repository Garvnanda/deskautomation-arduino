// DeskMatrix MCU firmware: a dumb framebuffer.
// Linux sends frames over arduino-router: frame(w0, w1, w2, w3, brightness).
// The 104 pixels (13x8, row-major) are packed MSB-first into four 32-bit words.
// If no frame arrives for 10 s, a "no link" pattern is shown.

#include <Arduino_LED_Matrix.h>
#include <Arduino_RouterBridge.h>

Arduino_LED_Matrix matrix;

volatile uint32_t words[4];
volatile uint8_t level = 7;      // 0..7 (3-bit grayscale)
volatile bool dirty = false;
volatile unsigned long lastFrame = 0;

void frame(uint32_t w0, uint32_t w1, uint32_t w2, uint32_t w3, int brightness) {
  words[0] = w0; words[1] = w1; words[2] = w2; words[3] = w3;
  level = constrain(brightness, 0, 7);
  lastFrame = millis();
  dirty = true;
}

void drawWords() {
  uint8_t buf[104];
  for (int i = 0; i < 104; i++) {
    bool on = (words[i / 32] >> (31 - (i % 32))) & 1;
    buf[i] = on ? level : 0;
  }
  matrix.draw(buf);
}

void drawNoLink() {
  // Blinking single dot in the bottom-right corner.
  uint8_t buf[104] = {0};
  if ((millis() / 500) % 2) buf[103] = 7;
  matrix.draw(buf);
}

void setup() {
  matrix.begin();
  matrix.setGrayscaleBits(3);
  Bridge.begin();
  Bridge.provide("frame", frame);
}

void loop() {
  if (dirty) {
    dirty = false;
    drawWords();
  } else if (millis() - lastFrame > 10000) {
    drawNoLink();
    delay(100);
  }
  delay(5);
}

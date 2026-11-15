/** clean-blue-ui 토큰. 값은 src/index.css 의 :root 에 있고 여기서는 이름만 붙인다. */
const channel = (name) => `rgb(var(--${name}) / <alpha-value>)`;

/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      screens: {
        // 갤럭시 폴드: 접은 커버 화면은 한 열, 펼친 내부 화면(넓고 정사각형에 가까움)은 두 열.
        unfolded: "600px",
      },
      colors: {
        canvas: channel("canvas"),
        surface: channel("surface"),
        royal: channel("royal"),
        "royal-soft": channel("royal-soft"),
        tint: channel("tint"),
        ink: channel("ink"),
        muted: channel("muted"),
        "muted-strong": channel("muted-strong"),
        faint: channel("faint"),
        line: channel("line"),
        frost: channel("frost"),
      },
      fontFamily: {
        sans: "var(--font-sans)",
      },
      boxShadow: {
        card: "var(--shadow-card)",
        lift: "var(--shadow-lift)",
        glow: "var(--shadow-glow)",
        dock: "var(--shadow-dock)",
      },
      backgroundImage: {
        hero: "var(--gradient-hero)",
      },
      borderRadius: {
        stage: "32px",
      },
    },
  },
  plugins: [],
};

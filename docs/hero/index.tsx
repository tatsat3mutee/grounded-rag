import {
  Badge,
  Bar,
  Collapse,
  Field,
  Icon,
  Stage,
  Stream,
  Text,
  Window,
  type Act,
} from "../../kit";
import "./grounded.css";

// Grounded RAG, the README hero. Copied from the ai-chat gold (question typed
// and sent, a status line, the answer streaming at its final height) and given
// one satellite: the Sources card that is the product's point.
//
// Beats: the question is typed and sent; "Searching product" while the Sources
// card resolves from skeleton to the two files it found; the answer streams
// with its citation; payoff: the cited phrase lights up in the source excerpt
// and "Verified against sources" lands under the answer. Plays once; the
// poster is the payoff.
//
// Real strings: the window title, the question, the answer, the status line,
// the cited file with its section and excerpt, the second file's name, the
// verified badge. Earlier turns and the second file's excerpt are bars.

const Q = "What's the refund window for annual plans?";
const A =
  "New customers on an annual plan can get a full refund within 30 days of the first payment [1]. After that, refunds are pro rata and only if uptime misses its commitment two months in a row [1].";
const WORDS = A.split(" ").length;
const EXCERPT =
  "New customers on an annual plan can request a full refund within 30 days of the first payment. After 30 days, annual plans are refunded pro rata only when…";
const CITED = new Set(["within", "30", "days"]);
let citedAt = -1;
const excerptWords = EXCERPT.split(" ");
for (let i = 0; i < excerptWords.length - 2; i++) {
  if (excerptWords[i] === "within" && excerptWords[i + 1] === "30") {
    citedAt = i;
    break;
  }
}
const isCitedWord = (w: string, i: number) => i >= citedAt && i < citedAt + 3 && CITED.has(w.replace(/[.,]/g, ""));
const isCitation = (w: string) => /^\[\d\]\.?$/.test(w);
// The same three words in the answer, lit with the excerpt at the payoff
const answerWords = A.split(" ");
const answerCitedAt = answerWords.findIndex((w, i) => w === "within" && answerWords[i + 1] === "30");
const isAnswerPhrase = (i: number) => i >= answerCitedAt && i < answerCitedAt + 3;

type S = {
  draft: string;
  sent: boolean;
  phase: "idle" | "searching" | "answer";
  found: number;
  words: number;
  verified: boolean;
};

const initial: S = { draft: "", sent: false, phase: "idle", found: 0, words: 0, verified: false };
const poster: S = { draft: "", sent: true, phase: "answer", found: 2, words: WORDS, verified: true };

async function script(a: Act<S>) {
  await a.wait(700);
  a.mark("type");
  await a.type("draft", Q, { interval: 38 });
  await a.wait(400);
  a.mark("send");
  a.put({ sent: true, draft: "" });
  await a.wait(450);
  a.mark("search");
  a.put({ phase: "searching" });
  await a.wait(650);
  a.put({ found: 1 });
  await a.wait(300);
  a.put({ found: 2 });
  await a.wait(700);
  a.mark("stream");
  a.put({ phase: "answer" });
  for (let i = 1; i <= WORDS; i++) {
    a.put({ words: i });
    await a.wait(42);
  }
  await a.wait(350);
  a.mark("verified");
  a.put({ verified: true });
  await a.wait(3200);
}

function SourceRow({ n, file, section, found, cited, children }: {
  n: number; file: string; section: string; found: boolean; cited: boolean; children: React.ReactNode;
}) {
  return (
    <div className="gr-src" data-found={found || undefined} data-cited={cited || undefined}>
      <div className="gr-src__head">
        <span className="gr-src__n">{n}</span>
        <div className="gr-src__skel" aria-hidden>
          <Bar w={128} h={7} r={3} tone={2} />
          <Bar w={64} h={6} r={3} tone={1} />
        </div>
        <div className="gr-src__name">
          <Text>{file}</Text>
          <Text className="gr-src__section">{section}</Text>
        </div>
      </div>
      <div className="gr-src__body">{children}</div>
    </div>
  );
}

export default function Grounded() {
  return (
    <Stage
      width={856}
      height={460}
      compact={{ below: 640, width: 358, height: 560 }}
      initial={initial}
      poster={poster}
      script={script}
      play="once"
      greys="cool"
      tokens={{ "--ss-accent": "#2f5d8c", "--ss-accent-soft": "#e3ecf6", "--ss-accent-text": "#24496f" }}
      className="gr"
    >
      {(s) => (
        <>
          <Window title="Grounded RAG" className="gr-win">
            <div className="gr-body">
              <div className="gr-thread">
                <div className="gr-turn gr-turn--past">
                  <Bar w="86%" h={8} r={4} tone={2} />
                  <Bar w="48%" h={8} r={4} tone={2} />
                </div>
                <div className="gr-turn gr-turn--past">
                  <Bar w={230} h={30} r={15} tone={1} className="gr-past-q" />
                  <Bar w="88%" h={8} r={4} tone={2} />
                  <Bar w="62%" h={8} r={4} tone={2} />
                </div>
                <Collapse open={s.sent} reveal="drop" from="below">
                  <div className="gr-q">
                    <Text>{Q}</Text>
                  </div>
                </Collapse>
                <Collapse open={s.phase === "searching"}>
                  <div className="gr-status">
                    <Icon name="search" size={14} />
                    <Text>Searching product</Text>
                  </div>
                </Collapse>
                <Collapse open={s.phase === "answer"}>
                  <div className="gr-a" data-on>
                    <Stream
                      className="gr-stream"
                      text={A}
                      count={s.words}
                      mark={(w, i) => isCitation(w) || (s.verified && isAnswerPhrase(i))}
                    />
                    <div className="gr-verified" data-on={s.verified || undefined}>
                      <Badge tone="success" icon={<Icon name="check" size={12} stroke={2} />}>
                        Verified against sources
                      </Badge>
                    </div>
                  </div>
                </Collapse>
              </div>
              <Field
                className="gr-composer"
                value={s.draft}
                placeholder="Ask about your documents"
                focused={!s.sent}
              />
            </div>
          </Window>

          <div className="gr-sources">
            <div className="gr-sources__title">Sources</div>
            <SourceRow n={1} file="refunds-and-cancellation.md" section="Refund window" found={s.found >= 1} cited={s.verified}>
              <div className="gr-excerpt-bars gr-excerpt-skel" aria-hidden>
                <Bar w="94%" h={6} r={3} tone={1} />
                <Bar w="88%" h={6} r={3} tone={1} />
                <Bar w="91%" h={6} r={3} tone={1} />
                <Bar w="56%" h={6} r={3} tone={1} />
              </div>
              <Stream
                className="gr-excerpt"
                text={EXCERPT}
                count={s.found >= 1 ? excerptWords.length : 0}
                mark={(w, i) => s.verified && isCitedWord(w, i)}
              />
            </SourceRow>
            <SourceRow n={2} file="pricing-and-billing.md" section="Annual billing" found={s.found >= 2} cited={false}>
              <div className="gr-excerpt-bars">
                <Bar w="94%" h={6} r={3} tone={1} />
                <Bar w="70%" h={6} r={3} tone={1} />
              </div>
            </SourceRow>
            {/* A third, lower-ranked hit running off the bottom: the list goes on */}
            <div className="gr-src gr-src--more" aria-hidden>
              <div className="gr-src__head">
                <span className="gr-src__n">3</span>
                <div className="gr-src__skel gr-src__skel--still">
                  <Bar w={112} h={7} r={3} tone={2} />
                  <Bar w={72} h={6} r={3} tone={1} />
                </div>
              </div>
            </div>
          </div>
        </>
      )}
    </Stage>
  );
}

import { localGuidance } from "./errors";

export function ErrorDetails({ message }: { message: string }) {
  const lines = message.split("\n").filter(Boolean);
  const steps = [
    ...lines
      .filter((line) => line.startsWith("• "))
      .map((line) => line.slice(2)),
    ...localGuidance(message),
  ];
  const reference = lines.find((line) => line.startsWith("Reference: "));
  const explanation = lines.filter(
    (line) => !line.startsWith("• ") && !line.startsWith("Reference: "),
  );
  return (
    <div className="error-details">
      {explanation.map((line, i) => (
        <p key={i}>{line}</p>
      ))}
      {!!steps.length && (
        <>
          <strong className="error-steps-title">What to do</strong>
          <ol>
            {steps.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </>
      )}
      {reference && <code className="error-reference">{reference}</code>}
    </div>
  );
}

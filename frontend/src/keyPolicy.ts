export type KeyPolicy = {
  mode: string;
  column: string;
  sequence: string;
  start: number;
  increment: number;
  groupColumns: string[];
  businessColumns: string[];
  onConflict: string;
};
export const defaultPolicy = (): KeyPolicy => ({
  mode: "KEEP",
  column: "",
  sequence: "",
  start: 1,
  increment: 1,
  groupColumns: [],
  businessColumns: [],
  onConflict: "ERROR",
});

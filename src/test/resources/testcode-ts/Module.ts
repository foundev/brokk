namespace MyModule {
    export class InnerClass {
        name: string = "Inner";
        constructor() {}
        doSomething(): void {}
    }
    export function innerFunc(): void {
        const nestedArrow = () => console.log("nested");
        nestedArrow();
    }
    export const innerConst: number = 42;

    export interface InnerInterface {
        id: number;
        describe(): string;
    }
    export enum InnerEnum { A, B }

    namespace NestedNamespace {
        export class DeeperClass {}
    }
}

// top-level item in same file
export class AnotherClass {}

export const topLevelArrow = (input: any): any => input;

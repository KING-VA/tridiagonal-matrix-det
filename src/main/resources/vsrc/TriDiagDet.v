module TriDiagDet #(
    parameter N = 16, // Ensure N is greater than 2 and less than or equal to 16 for address space
    parameter WIDTH = 16 // Max width of data is 16 bits to accommodate 32 bits for the determinant output
)(
    input clk,
    input rst,
    input start,
    input ack,
    input [WIDTH*(N-1)-1:0] a_flat,  // Flattened a[0] to a[N-2] from LSB to MSB
    input [WIDTH*N-1:0] b_flat,      // Flattened b[0] to b[N-1] from LSB to MSB
    input [WIDTH*(N-1)-1:0] c_flat,  // Flattened c[0] to c[N-2] from LSB to MSB
    output reg done,
    output reg signed [2*WIDTH-1:0] det,
);
    // FSM states
    localparam IDLE = 1'd0, CALC = 1'd1;
    reg state;

    // Internal loop index
    reg [$clog2(N):0] i;

    // Recurrence result storage
    reg signed [2*WIDTH-1:0] D0, D1, D2;

    always @(posedge clk or posedge rst) begin
        if (rst) begin
            state <= IDLE;
            i <= 0;
            D0 <= 0;
            D1 <= 0;
            D2 <= 0;
            det <= 0;
            done <= 0;
        end else begin
            case (state)
                IDLE: begin
                    done <= 0;
                    if (start) begin
                        D0 <= {{(32-WIDTH){b_flat[WIDTH-1]}}, b_flat[WIDTH-1:0]};
                        D1 <= $signed(b_flat[2*WIDTH-1:WIDTH])*$signed(b_flat[WIDTH-1:0]) - $signed(a_flat[WIDTH-1:0])*$signed(c_flat[WIDTH-1:0]);
                        i <= 2;
                        state <= CALC;
                    end
                end

                CALC: begin
                    if (i < N) begin
                        D2 <= $signed(b_flat[i*WIDTH +: WIDTH])*D1 - $signed(a_flat[(i-1)*WIDTH +: WIDTH])*$signed(c_flat[(i-1)*WIDTH +: WIDTH])*D0;
                        D0 <= D1;
                        D1 <= D2;
                        i <= i + 1;
                    end else begin
                        if (ack) begin
                            state <= IDLE;
                        end else begin
                            det <= D2;
                            done <= 1;
                        end
                    end
                end
            endcase
        end
    end
endmodule

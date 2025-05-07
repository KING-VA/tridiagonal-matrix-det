module tridiag_det_core #(
    parameter N = 16,       // Ensure N is less than or equal to 16 for address space and greater than 2
    parameter WIDTH = 16    // Bit-width of matrix entries max is 16 bit to accommodate 32 bits for determinant output
)(
    input wire clk,
    input wire rst,

    // Write enable signal
    input wire we,

    // Address space
    // 0x00: Control register (start) - Set to write_data[0] 1 to start the calculation
    // 0x01: Status register (done) - Read to check if calculation is done
    // 0x02: Ack (clear done) - Set address to 0x02 and we to high to clear done signal
    // 0x10: a[0] to a[N-2]
    // 0x20: b[0] to b[N-1]
    // 0x30: c[0] to c[N-2]
    // 0x40: det (determinant result)
    input wire [7:0] address,

    // Data bus
    input wire [16:0] write_data,
    output wire [31:0] read_data
);

    // Internal storage
    reg signed [WIDTH-1:0] a_reg [0:N-2];
    reg signed [WIDTH-1:0] b_reg [0:N-1];
    reg signed [WIDTH-1:0] c_reg [0:N-2];

    wire signed [WIDTH*(N-1)-1:0] a_flat;
    wire signed [WIDTH*N-1:0]     b_flat;
    wire signed [WIDTH*(N-1)-1:0] c_flat;

    // Flatten arrays for core module
    genvar idx;
    generate
        for (idx = 0; idx < N-1; idx = idx + 1) begin : FLATTEN_A_C
            assign a_flat[(idx+1)*WIDTH-1 -: WIDTH] = a_reg[idx];
            assign c_flat[(idx+1)*WIDTH-1 -: WIDTH] = c_reg[idx];
        end
        for (idx = 0; idx < N; idx = idx + 1) begin : FLATTEN_B
            assign b_flat[(idx+1)*WIDTH-1 -: WIDTH] = b_reg[idx];
        end
    endgenerate

    // Control and status
    reg start_reg = 0;
    reg done_reg = 0;
    
    // FSM states
    localparam IDLE = 1'b0, CALC = 1'b1;
    reg current_state = IDLE; // Current state of the FSM (0: IDLE, 1: CALC)

    wire done_wire;
    wire signed [2*WIDTH-1:0] det_wire;
    wire state_out;

    // Instantiate the determinant computation core
    tridiag_det_algo #(.N(N), .WIDTH(WIDTH)) core (
        .clk(clk),
        .rst(rst),
        .start(start_reg),
        .a_flat(a_flat),
        .b_flat(b_flat),
        .c_flat(c_flat),
        .done(done_wire),
        .det(det_wire),
        .state_out(state_out)
    );

    // Reset, start, done, and acknowledgment logic
    integer i;
    always @(posedge clk or posedge rst) begin
        if (rst) begin
            done_reg <= 0;
            start_reg <= 0;
            current_state <= IDLE;
            for (i = 0; i < N-1; i = i + 1) begin
                a_reg[i] <= 0;
                c_reg[i] <= 0;
            end
            for (i = 0; i < N; i = i + 1) begin
                b_reg[i] <= 0;
            end
        end else begin
            if (start_reg) begin
                current_state <= CALC; // Move to CALC state
                start_reg <= 0; // Clear start after one cycle
            end
            // If done signal is high, set done_reg to 1 and move to IDLE state
            if (done_wire) begin
                done_reg <= 1; // Set done when core is done
                current_state <= IDLE; // Move to IDLE state
            end

            // Clear done if writing to ACK
            if (we && address == 8'h02) begin
                done_reg <= 0;
            end
        end
    end

    // Write logic
    always @(posedge clk) begin
        if (we && current_state == IDLE && !done_reg) begin // Only write if in IDLE state and not done_reg (previous calculation was acked)
            case (address)
                8'h00: start_reg <= write_data[0]; // Start signal
                default: begin
                    if (address >= 8'h10 && address < 8'h10 + (N - 1))
                        a_reg[address - 8'h10] <= write_data[WIDTH-1:0];
                    else if (address >= 8'h20 && address < 8'h20 + N)
                        b_reg[address - 8'h20] <= write_data[WIDTH-1:0];
                    else if (address >= 8'h30 && address < 8'h30 + (N - 1))
                        c_reg[address - 8'h30] <= write_data[WIDTH-1:0];
                end
            endcase
        end
    end

    // Read logic
    reg [31:0] read_data_reg;
    assign read_data = read_data_reg;

    always @(*) begin
        case (address)
            8'h01: read_data_reg = {31'b0, done_reg}; // Status
            8'h40: read_data_reg = det_wire[31:0];    // Determinant (LSBs only)
            default: begin
                if (address >= 8'h10 && address < 8'h10 + (N - 1))
                    read_data_reg = {{(32-WIDTH){a_reg[address - 8'h10][WIDTH-1]}}, a_reg[address - 8'h10]};
                else if (address >= 8'h20 && address < 8'h20 + N)
                    read_data_reg = {{(32-WIDTH){b_reg[address - 8'h20][WIDTH-1]}}, b_reg[address - 8'h20]};
                else if (address >= 8'h30 && address < 8'h30 + (N - 1))
                    read_data_reg = {{(32-WIDTH){c_reg[address - 8'h30][WIDTH-1]}}, c_reg[address - 8'h30]};
                else
                    read_data_reg = 32'h0;
            end
        endcase
    end

endmodule
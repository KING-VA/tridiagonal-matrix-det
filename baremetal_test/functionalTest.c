#include "rocc.h"
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <inttypes.h>

#define READIN_A     0
#define READIN_C     1
#define READIN_B     2
#define START_COMP   4
#define QUERYSTATUS  5

#define N 16
#define WIDTH 2 // 16 bits = 2 bytes

int16_t A[N - 1] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
int16_t B[N]     = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
int16_t C[N - 1] = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

int32_t tri_diag_model(int16_t* a, int16_t* b, int16_t* c) {
    int32_t D0 = b[0];
    int32_t D1 = (int32_t)b[1]*b[0] - (int32_t)a[0]*c[0];
    int32_t D2;

    for (int i = 2; i < N; ++i) {
        D2 = (int32_t)b[i] * D1 - (int32_t)a[i-1]*c[i-1]*D0;
        D0 = D1;
        D1 = D2;
    }

    return D2;
}

int main() {
    // Allocate DMA-compatible memory
    int16_t *dma_A = (int16_t*) aligned_alloc(32, 16 * sizeof(int16_t));
    int16_t *dma_C = (int16_t*) aligned_alloc(32, 16 * sizeof(int16_t));
    int16_t *dma_B = (int16_t*) aligned_alloc(32, 16 * sizeof(int16_t));
    int32_t *result = (int32_t*) aligned_alloc(4, sizeof(int32_t));

    // Copy A and C with padding (last element = 0)
    for (int i = 0; i < N - 1; i++) {
        dma_A[i] = A[i];
        dma_C[i] = C[i];
    }
    dma_A[N - 1] = 0;
    dma_C[N - 1] = 0;

    // Copy B directly
    for (int i = 0; i < N; i++) {
        dma_B[i] = B[i];
    }

    // Issue READIN_A
    asm volatile("fence");
    ROCC_INSTRUCTION_SS(0, (uint64_t)dma_A, 16, READIN_A);
    asm volatile("fence");

    // Issue READIN_C
    asm volatile("fence");
    ROCC_INSTRUCTION_SS(0, (uint64_t)dma_C, 16, READIN_C);
    asm volatile("fence");

    // Issue READIN_B
    asm volatile("fence");
    ROCC_INSTRUCTION_SS(0, (uint64_t)dma_B, 16, READIN_B);
    asm volatile("fence");

    // Start computation
    ROCC_INSTRUCTION_SS(0, (uint64_t)result, 0, START_COMP);
    asm volatile("fence");

    // Wait for accelerator to finish
    uint64_t status = 0;
    do {
        ROCC_INSTRUCTION_D(0, status, QUERYSTATUS);
    } while (status != 6); // Assuming 6 = Done

    int32_t expected_result = tri_diag_model(A, B, C);
    int32_t accel_result = *result;

    printf("Expected result: %d\n", expected_result);
    printf("Accelerator result: %d\n", accel_result);

    return !(expected_result == accel_result);
}

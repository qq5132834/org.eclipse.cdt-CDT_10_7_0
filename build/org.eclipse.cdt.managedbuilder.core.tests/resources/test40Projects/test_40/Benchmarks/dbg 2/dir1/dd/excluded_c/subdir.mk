################################################################################
# Automatically-generated file. Do not edit!
################################################################################

# Add inputs and outputs from these tool invocations to the build variables 
CPP_SRCS += \
../dir1/dd/excluded_c/inc.cpp 

CPP_DEPS += \
./dir1/dd/excluded_c/inc.d 

OBJS += \
./dir1/dd/excluded_c/inc.o 


# Each subdirectory must supply rules for building sources it contributes
dir1/dd/excluded_c/%.o: ../dir1/dd/excluded_c/%.cpp dir1/dd/excluded_c/subdir.mk
	@echo 'Building file: $<'
	@echo 'Invoking: Test 4.0 ToolName.compiler.gnu.cpp'
	g++ -O2 -g -Wall -c -fmessage-length=0 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$@" -o "$@" "$<"
	@echo 'Finished building: $<'
	@echo ' '


clean: clean-dir1-2f-dd-2f-excluded_c

clean-dir1-2f-dd-2f-excluded_c:
	-$(RM) ./dir1/dd/excluded_c/inc.d ./dir1/dd/excluded_c/inc.o

.PHONY: clean-dir1-2f-dd-2f-excluded_c


################################################################################
# Automatically-generated file. Do not edit!
################################################################################

# Add inputs and outputs from these tool invocations to the build variables 
CPP_SRCS += \
../d1/q.cpp \
../d1/u.cpp \
../d1/w.cpp 

CPP_DEPS += \
./d1/q.d \
./d1/u.d \
./d1/w.d 

OBJS += \
./d1/q.o \
./d1/u.o \
./d1/w.o 


# Each subdirectory must supply rules for building sources it contributes
d1/%.o: ../d1/%.cpp d1/subdir.mk
	@echo 'Building file: $<'
	@echo 'Invoking: Test 4.0 ToolName.compiler.gnu.cpp'
	g++ -Id1_rel/path -I../d1_proj/rel/path -I/d1_abs/path -Ic:/d1_abs/path -Irel/path -I../proj/rel/path -I/abs/path -Ic:/abs/path -I"${WorkspaceDirPath}/test_40/dir1/dir2/dir3" -I"${WorkspaceDirPath}/test_40" -I"D:\docs\incs" -I"D:\d1_docs\incs" -O0 -g3 -Wall -c -fmessage-length=0 -MMD -MP -MF"$(@:%.o=%.d)" -MT"$@" -o "$@" "$<"
	@echo 'Finished building: $<'
	@echo ' '


clean: clean-d1

clean-d1:
	-$(RM) ./d1/q.d ./d1/q.o ./d1/u.d ./d1/u.o ./d1/w.d ./d1/w.o

.PHONY: clean-d1

